package com.hertz.hertz_be.domain.channel.service;

import com.corundumstudio.socketio.SocketIOServer;
import com.hertz.hertz_be.domain.channel.dto.request.SignalMatchingRequestDto;
import com.hertz.hertz_be.domain.channel.dto.response.*;
import com.hertz.hertz_be.global.socketio.dto.SocketIoMessageResponse;
import com.hertz.hertz_be.domain.channel.entity.*;
import com.hertz.hertz_be.domain.channel.entity.enums.Category;
import com.hertz.hertz_be.domain.channel.entity.enums.MatchingStatus;
import com.hertz.hertz_be.domain.channel.dto.request.SendSignalRequestDto;
import com.hertz.hertz_be.domain.channel.exception.*;
import com.hertz.hertz_be.domain.channel.repository.*;
import com.hertz.hertz_be.domain.channel.repository.projection.ChannelRoomProjection;
import com.hertz.hertz_be.domain.channel.repository.projection.RoomWithLastSenderProjection;
import com.hertz.hertz_be.domain.interests.entity.enums.InterestsCategoryType;
import com.hertz.hertz_be.domain.interests.repository.UserInterestsRepository;
import com.hertz.hertz_be.domain.interests.service.InterestsService;
import com.hertz.hertz_be.domain.user.entity.User;
import com.hertz.hertz_be.domain.user.exception.UserException;
import com.hertz.hertz_be.domain.user.repository.UserRepository;
import com.hertz.hertz_be.global.util.AESUtil;
import com.hertz.hertz_be.global.common.ResponseCode;
import com.hertz.hertz_be.global.exception.AiServerBadRequestException;
import com.hertz.hertz_be.global.exception.AiServerErrorException;
import com.hertz.hertz_be.global.exception.AiServerNotFoundException;
import com.hertz.hertz_be.global.exception.InternalServerErrorException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;
    private final TuningRepository tuningRepository;
    private final TuningResultRepository tuningResultRepository;
    private final UserInterestsRepository userInterestsRepository;
    private final SignalRoomRepository signalRoomRepository;
    private final SignalMessageRepository signalMessageRepository;
    private final ChannelRoomRepository channelRoomRepository;
    private final InterestsService interestsService;
    private final AsyncChannelService asyncChannelService;
    private final WebClient webClient;
    private final AESUtil aesUtil;
    private SocketIOServer socketIOServer;

    @Autowired
    public ChannelService(UserRepository userRepository,
                          TuningRepository tuningRepository,
                          TuningResultRepository tuningResultRepository,
                          UserInterestsRepository userInterestsRepository,
                          SignalRoomRepository signalRoomRepository,
                          SignalMessageRepository signalMessageRepository,
                          ChannelRoomRepository channelRoomRepository,
                          InterestsService interestsService,
                          AsyncChannelService asyncChannelService,
                          SseChannelService matchingStatusScheduler,
                          AESUtil aesUtil,
                          @Value("${ai.server.ip}") String aiServerIp, SocketIOServer socketIOServer) {
        this.userRepository = userRepository;
        this.tuningRepository = tuningRepository;
        this.tuningResultRepository = tuningResultRepository;
        this.userInterestsRepository = userInterestsRepository;
        this.signalMessageRepository = signalMessageRepository;
        this.signalRoomRepository = signalRoomRepository;
        this.channelRoomRepository = channelRoomRepository;
        this.interestsService = interestsService;
        this.asyncChannelService = asyncChannelService;
        this.aesUtil = aesUtil;
        this.webClient = WebClient.builder().baseUrl(aiServerIp).build();
        this.socketIOServer = socketIOServer;
    }

    @Transactional
    public SendSignalResponseDto sendSignal(Long senderUserId, SendSignalRequestDto dto) {
        User sender = userRepository.findById(senderUserId)
                .orElseThrow(UserNotFoundException::new);

        User receiver = userRepository.findById(dto.getReceiverUserId())
                .orElseThrow(UserWithdrawnException::new);


        String userPairSignal = generateUserPairSignal(sender.getId(), receiver.getId());
        Optional<SignalRoom> existingRoom = signalRoomRepository.findByUserPairSignal(userPairSignal);
        if (existingRoom.isPresent()) {
            throw new AlreadyInConversationException();
        } else if (Objects.equals(sender.getId(), receiver.getId())) {
            throw new CannotSendSignalToSelfException();
        }

        SignalRoom signalRoom = SignalRoom.builder()
                .senderUser(sender)
                .receiverUser(receiver)
                .category(Category.FRIEND)
                .senderMatchingStatus(MatchingStatus.SIGNAL)
                .receiverMatchingStatus(MatchingStatus.SIGNAL)
                .userPairSignal(userPairSignal)
                .build();

        try {
            signalRoomRepository.save(signalRoom);
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyInConversationException();
        }

        String encryptMessage = aesUtil.encrypt(dto.getMessage());

        SignalMessage signalMessage = SignalMessage.builder()
                .signalRoom(signalRoom)
                .senderUser(sender)
                .message(encryptMessage)
                .isRead(false)
                .build();
        signalMessageRepository.save(signalMessage);

        entityManager.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                asyncChannelService.sendNewMessageNotifyToPartner(signalMessage, receiver.getId(), true);
            }
        });

        return new SendSignalResponseDto(signalRoom.getId());
    }

    public static String generateUserPairSignal(Long userId1, Long userId2) {
        Long min = Math.min(userId1, userId2);
        Long max = Math.max(userId1, userId2);
        return min + "_" + max;
    }

    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);
    }

    public boolean hasSelectedInterests(User user) {
        return userInterestsRepository.existsByUser(user);
    }

    @Transactional
    public TuningResponseDto getTunedUser(Long userId) {
        User requester = getUserById(userId);

        if (!hasSelectedInterests(requester)) {
            throw new InterestsNotSelectedException();
        }

        Tuning tuning = getOrCreateTuning(requester);

        if (!tuningResultRepository.existsByTuning(tuning)) {
            boolean saved = fetchAndSaveTuningResultsFromAiServer(userId, tuning);
            if (!saved) return null;
        }

        Optional<TuningResult> optionalResult = tuningResultRepository.findFirstByTuningOrderByLineupAsc(tuning);
        if (optionalResult.isEmpty()) {
            boolean saved = fetchAndSaveTuningResultsFromAiServer(userId, tuning);
            if (!saved) return null;

            optionalResult = tuningResultRepository.findFirstByTuningOrderByLineupAsc(tuning);
            if (optionalResult.isEmpty()) return null;
        }

        TuningResult topResult = optionalResult.get();
        tuningResultRepository.delete(topResult);

        User matchedUser = topResult.getMatchedUser();
        if (matchedUser == null || matchedUser.getId() == null) return null;

        return buildTuningResponseDTO(userId, matchedUser);
    }


    private boolean fetchAndSaveTuningResultsFromAiServer(Long userId, Tuning tuning) {
        Map<String, Object> responseMap = requestTuningFromAiServer(userId);
        String code = (String) responseMap.get("code");

        switch (code) {
            case ResponseCode.TUNING_SUCCESS_BUT_NO_MATCH -> {
                return false;
            }
            case ResponseCode.TUNING_BAD_REQUEST -> {
                throw new AiServerBadRequestException();
            }
            case ResponseCode.TUNING_NOT_FOUND_USER -> {
                throw new AiServerNotFoundException();
            }
            case ResponseCode.TUNING_INTERNAL_SERVER_ERROR -> {
                throw new AiServerErrorException(ResponseCode.TUNING_INTERNAL_SERVER_ERROR);
            }
            case ResponseCode.TUNING_SUCCESS -> {
                Object dataObj = responseMap.get("data");
                if (!(dataObj instanceof Map data)) {
                    throw new AiServerErrorException(ResponseCode.TUNING_NOT_FOUND_DATA);
                }

                List<Integer> userIdList = (List<Integer>) data.get("userIdList");
                if (userIdList == null || userIdList.isEmpty()) {
                    throw new AiServerErrorException(ResponseCode.TUNING_NOT_FOUND_LIST);
                }

                saveTuningResults(userIdList, tuning);
                return true;
            }
            default -> throw new InternalServerErrorException();
        }
    }


    private Map<String, Object> requestTuningFromAiServer(Long userId) {
        String uri = "/api/v1/tuning?userId=" + userId;
        Map<String, Object> responseMap = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        if (responseMap == null || !responseMap.containsKey("code")) {
            throw new AiServerErrorException(ResponseCode.TUNING_INTERNAL_SERVER_ERROR);
        }
        return responseMap;
    }

    private Tuning getOrCreateTuning(User user) {
        return tuningRepository.findByUserAndCategory(user, Category.FRIEND)
                .orElseGet(() -> tuningRepository.save(
                        Tuning.builder()
                                .user(user)
                                .category(Category.FRIEND)
                                .build()));
    }

    private void saveTuningResults(List<Integer> userIdList, Tuning tuning) {
        int lineup = 1;
        User requester = tuning.getUser();

        for (Integer matchedUserId : userIdList) {
            Long matchedId = Long.valueOf(matchedUserId);

            User matchedUser = userRepository.findById(matchedId)
                    .orElseThrow(UserWithdrawnException::new);

            if (!hasSelectedInterests(matchedUser)) {
                continue;
            }

            boolean alreadyExists = signalRoomRepository.existsBySenderUserAndReceiverUser(requester, matchedUser)
                    || signalRoomRepository.existsBySenderUserAndReceiverUser(matchedUser, requester);

            if (alreadyExists) continue;

            tuningResultRepository.save(
                    TuningResult.builder()
                            .tuning(tuning)
                            .matchedUser(matchedUser)
                            .lineup(lineup++)
                            .build()
            );
        }
    }

    private TuningResponseDto buildTuningResponseDTO(Long requesterId, User target) {
        Map<String, String> keywords = interestsService.getUserKeywords(target.getId());
        Map<String, List<String>> requesterInterests = interestsService.getUserInterests(requesterId);
        Map<String, List<String>> targetInterests = interestsService.getUserInterests(target.getId());
        Map<String, List<String>> sameInterests = interestsService.extractSameInterests(requesterInterests, targetInterests);

        return new TuningResponseDto(
                target.getId(),
                target.getProfileImageUrl(),
                target.getNickname(),
                target.getGender(),
                target.getOneLineIntroduction(),
                keywords,
                sameInterests
        );
    }


    @Transactional(readOnly = true)
    public boolean hasNewMessages(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        List<SignalRoom> allRooms = Stream.concat(
                user.getSentSignalRooms().stream(),
                user.getReceivedSignalRooms().stream()
        ).collect(Collectors.toList());

        if (allRooms.isEmpty()) return false;

        return signalMessageRepository.existsBySignalRoomInAndSenderUserNotAndIsReadFalse(allRooms, user);

    }

    public Map<String, List<String>> getUserInterests(Long userId) {
        Map<String, List<String>> interestsMap = new LinkedHashMap<>();

        userInterestsRepository.findByUserId(userId).stream()
                .filter(ui -> ui.getCategoryItem().getCategory().getCategoryType() == InterestsCategoryType.INTEREST)
                .forEach(ui -> {
                    String categoryName = ui.getCategoryItem().getCategory().getName();
                    String itemName = ui.getCategoryItem().getName();
                    interestsMap.computeIfAbsent(categoryName, k -> new ArrayList<>()).add(itemName);
                });

        return interestsMap;
    }

    public Map<String, List<String>> extractSameInterests(Map<String, List<String>> interests1, Map<String, List<String>> interests2) {
        Map<String, List<String>> sameInterests = new LinkedHashMap<>();

        for (String category : interests1.keySet()) {
            List<String> list1 = interests1.getOrDefault(category, Collections.emptyList());
            List<String> list2 = interests2.getOrDefault(category, Collections.emptyList());

            Set<String> common = new HashSet<>(list1);
            common.retainAll(list2);

            if (!common.isEmpty()) {
                sameInterests.put(category, List.of(common.iterator().next()));
            } else {
                sameInterests.put(category, Collections.emptyList());
            }
        }

        return sameInterests;

    }

    public ChannelListResponseDto getPersonalSignalRoomList(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ChannelRoomProjection> result = signalRoomRepository.findChannelRoomsWithPartnerAndLastMessage(userId, pageable);

        if (result.isEmpty()) {
            return null;
        }

        List<ChannelSummaryDto> list = result.getContent().stream()
                .filter(p -> {
                    boolean isSender = userId.equals(p.getSenderUserId());
                    LocalDateTime exitedAt = isSender ? p.getSenderExitedAt() : p.getReceiverExitedAt();
                    return exitedAt == null;
                })
                .map(p -> ChannelSummaryDto.fromProjectionWithDecrypt(p, aesUtil))
                .toList();

        return new ChannelListResponseDto(list, result.getNumber(), result.getSize(), result.isLast());
    }

    @Transactional
    public ChannelRoomResponseDto getChannelRoom(Long roomId, Long userId, int page, int size) {
        SignalRoom room = signalRoomRepository.findById(roomId)
                .orElseThrow(ChannelNotFoundException::new);

        if (!room.isParticipant(userId)) {
            throw new ForbiddenChannelException();
        }

        if (room.isUserExited(userId)) {
            throw new AlreadyExitedChannelRoomException();
        }

        Long partnerId = room.getPartnerUser(userId).getId();

        User partner = userRepository.findByIdAndDeletedAtIsNull(partnerId)
                .orElseThrow(() -> new UserException("USER_DEACTIVATED", "상대방이 탈퇴한 사용자입니다."));

        Optional<RoomWithLastSenderProjection> result = signalMessageRepository.findRoomsWithLastSender(roomId);

        if(result.isPresent()){
            RoomWithLastSenderProjection lastSender = result.get();
            if (!Objects.equals(lastSender.getLastSenderId(), userId)) {
                signalMessageRepository.markAllMessagesAsReadByRoomId(roomId);
            }
        }

        asyncChannelService.notifyMatchingConvertedInChannelRoom(room, userId);

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "sendAt"));
        Page<SignalMessage> messagePage = signalMessageRepository.findBySignalRoom_Id(roomId, pageable);

        List<ChannelRoomResponseDto.MessageDto> messages = messagePage.getContent().stream()
                .map(msg -> ChannelRoomResponseDto.MessageDto.fromProjectionWithDecrypt(msg, aesUtil))
                .toList();

        entityManager.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                asyncChannelService.updateNavbarMessageNotification(userId);
            }
        });

        return ChannelRoomResponseDto.of(roomId, partner, room.getRelationType(), messages, messagePage);
    }

    @Transactional
    public void sendChannelMessage(Long roomId, Long userId, SendSignalRequestDto response) {
        // 1. 메세지 DB 저장
        SignalRoom room = signalRoomRepository.findById(roomId)
                .orElseThrow(ChannelNotFoundException::new);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ResponseCode.USER_NOT_FOUND, "사용자가 존재하지 않습니다."));

        if (!room.isParticipant(userId)) {
            throw new ForbiddenChannelException();
        }

        Long partnerId = room.getPartnerUser(userId).getId();
        userRepository.findByIdAndDeletedAtIsNull(partnerId)
                .orElseThrow(() -> new UserException(ResponseCode.USER_DEACTIVATED, "상대방이 탈퇴한 사용자입니다."));

        String encryptMessage = aesUtil.encrypt(response.getMessage());

        SignalMessage signalMessage = SignalMessage.builder()
                .signalRoom(room)
                .senderUser(user)
                .message(encryptMessage)
                .build();

        signalMessageRepository.save(signalMessage);

        // 2. 메세지 WebSocket 전송
        String roomKey = "room-" + roomId;
        socketIOServer.getRoomOperations(roomKey)
                        .sendEvent("receive_message", SocketIoMessageResponse.from(signalMessage));

        entityManager.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                asyncChannelService.notifyMatchingConverted(room);
                asyncChannelService.sendNewMessageNotifyToPartner(signalMessage, partnerId, false);
            }
        });
    }

    @Transactional
    public String channelMatchingStatusUpdate(Long userId, SignalMatchingRequestDto response, MatchingStatus matchingStatus) {
        SignalRoom room = signalRoomRepository.findById(response.getChannelRoomId())
                .orElseThrow(ChannelNotFoundException::new);

        int updatedSender = signalRoomRepository.updateSenderMatchingStatus(room.getId(), userId, matchingStatus);
        int updatedReceiver = signalRoomRepository.updateReceiverMatchingStatus(room.getId(), userId, matchingStatus);

        if (updatedSender == 0 && updatedReceiver == 0) {
            throw new ForbiddenChannelException();
        }

        entityManager.flush();


        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                asyncChannelService.notifyMatchingResultToPartner(room, userId, matchingStatus);
                asyncChannelService.createMatchingAlarm(room, userId);
            }
        });


        /**
         * 매칭 수락/거절 후 현재 관계 반환
         */
        if(matchingStatus == MatchingStatus.MATCHED) {
            return signalRoomRepository.findMatchResultByUser(userId, room.getId());
        } else {
            return ResponseCode.MATCH_REJECTION_SUCCESS;
        }
    }


    @Transactional
    public void leaveChannelRoom(Long roomId, Long userId) {
        SignalRoom room = signalRoomRepository.findById(roomId)
                .orElseThrow(ChannelNotFoundException::new);
        userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        room.leaveChannelRoom(userId);
    }
}
