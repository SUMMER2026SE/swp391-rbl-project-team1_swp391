package com.sportvenue.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.dto.request.AiChatTurnRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.PageResponse;
import com.sportvenue.dto.response.StadiumResponse;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.User;
import com.sportvenue.entity.enums.StadiumNodeType;
import com.sportvenue.entity.enums.StadiumStatus;
import com.sportvenue.repository.AiUsageLogRepository;
import com.sportvenue.repository.BookingRepository;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.security.UserPrincipal;
import com.sportvenue.service.BookingService;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.PublicStadiumService;
import com.sportvenue.service.ai.handler.BookingHandler;
import com.sportvenue.service.ai.handler.BookingStatusHandler;
import com.sportvenue.service.ai.handler.CancelBookingHandler;
import com.sportvenue.service.ai.handler.GetPriceHandler;
import com.sportvenue.service.ai.handler.JoinMatchHandler;
import com.sportvenue.service.ai.handler.MatchRequestHandler;
import com.sportvenue.service.ai.handler.MyBookingsHandler;
import com.sportvenue.service.ai.handler.PolicyHandler;
import com.sportvenue.service.ai.handler.RecommendTimeHandler;
import com.sportvenue.service.ai.handler.SlotAvailabilityHandler;
import com.sportvenue.service.ai.handler.StadiumSearchHandler;
import com.sportvenue.util.RelativeDateParser;
import com.sportvenue.util.location.VietnamLocationResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiChatE2ETest {

    @Mock
    private GroqClient groqClient;
    @Mock
    private PublicStadiumService publicStadiumService;
    @Mock
    private SportTypeRepository sportTypeRepository;
    @Mock
    private StadiumRepository stadiumRepository;
    @Mock
    private VietnamLocationResolver locationResolver;
    @Mock
    private MaintenanceScheduleService maintenanceScheduleService;
    @Mock
    private BookingService bookingService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private AiUsageLogRepository aiUsageLogRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private MyBookingsHandler myBookingsHandler;
    @Mock
    private BookingStatusHandler bookingStatusHandler;
    @Mock
    private CancelBookingHandler cancelBookingHandler;
    @Mock
    private GetPriceHandler getPriceHandler;
    @Mock
    private RecommendTimeHandler recommendTimeHandler;
    @Mock
    private com.sportvenue.service.ai.ParamNormalizer paramNormalizer;
    @Mock
    private com.sportvenue.service.ai.IntentValidator intentValidator;

    private AiChatServiceImpl aiChatService;
    private AiConversationContextService conversationContextService;

    @BeforeEach
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        when(paramNormalizer.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(intentValidator.validate(any())).thenAnswer(invocation -> {
            com.sportvenue.service.ai.ExtractedIntentResult result = invocation.getArgument(0);
            return new IntentValidator.ValidationResult(true, result, "PASS", null);
        });

        conversationContextService = new AiConversationContextService(redisTemplate, new ObjectMapper());

        StadiumSearchHandler stadiumSearchHandler = new StadiumSearchHandler(
                publicStadiumService, sportTypeRepository, stadiumRepository,
                mock(com.sportvenue.mapper.StadiumMapper.class), locationResolver,
                maintenanceScheduleService, conversationContextService);

        SlotAvailabilityHandler slotAvailabilityHandler = new SlotAvailabilityHandler(
                stadiumRepository, bookingService, maintenanceScheduleService, conversationContextService);

        RelativeDateParser dateParser = new RelativeDateParser();
        BookingHandler bookingHandler = new BookingHandler(
                bookingService, stadiumRepository, userRepository,
                bookingRepository, maintenanceScheduleService, conversationContextService,
                sportTypeRepository, dateParser);

        java.time.Clock fixedClock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-07-15T01:00:00Z"),
                java.time.ZoneId.of("Asia/Ho_Chi_Minh")
        );
        stadiumSearchHandler.setClock(fixedClock);
        slotAvailabilityHandler.setClock(fixedClock);
        bookingHandler.setClock(fixedClock);
        dateParser.setClock(fixedClock);

        MatchRequestHandler matchRequestHandler = mock(MatchRequestHandler.class);
        PolicyHandler policyHandler = mock(PolicyHandler.class);
        JoinMatchHandler joinMatchHandler = mock(JoinMatchHandler.class);

        aiChatService = new AiChatServiceImpl(groqClient, stadiumSearchHandler, slotAvailabilityHandler,
                matchRequestHandler, policyHandler, bookingHandler, joinMatchHandler, myBookingsHandler,
                bookingStatusHandler, cancelBookingHandler, getPriceHandler, recommendTimeHandler,
                aiUsageLogRepository, paramNormalizer, intentValidator, conversationContextService, null, null);
        aiChatService.setClock(fixedClock);
    }

    private AiChatTurnRequest request(String message) {
        return AiChatTurnRequest.builder().message(message).build();
    }

    @Test
    void testSearchBySpecificName_DistrictParsedAsName() {
        lenient().when(groqClient.chatJson(any(), any(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"search_stadiums\",\"message\":\"Ok\",\"params\":{\"district\":\"chi Lăng\"}}",
                        0, 0, 0));

        lenient().when(locationResolver.deriveFromAddress("chi Lăng"))
                .thenReturn(new VietnamLocationResolver.LocationMatch(null, null));

        StadiumResponse responseObj = new StadiumResponse();
        responseObj.setStadiumId(233);
        responseObj.setStadiumName("Sân 1");
        lenient().when(publicStadiumService.searchStadiums(any())).thenReturn(
                PageResponse.<StadiumResponse>builder().content(List.of(responseObj)).build());

        AiChatTurnResponse response = aiChatService.handleChat(request("cần tìm sân bóng đá chi Lăng"), null, "s:test");

        assertThat(response.getIntent()).isEqualTo("search_stadiums");
        assertThat(response.getStadiums()).hasSize(1);
    }

    @Test
    void testMultiTurnBookingFlow_Search_GetSlots_Book() {
        User mockUser = new User();
        mockUser.setUserId(1);
        mockUser.setEmail("user@test.com");
        UserPrincipal userPrincipal = new UserPrincipal(mockUser);

        // Bước 1: Search
        lenient().when(groqClient.chatJson(any(), anyString(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"search_stadiums\",\"message\":\"ok\",\"params\":{\"keyword\":\"Thủ Đức\"}}", 0, 0, 0))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"get_slots\",\"message\":\"ok\",\"params\":{\"keyword\":\"Thủ Đức\",\"date\":\"2026-07-15\"}}", 0, 0, 0))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"create_booking\",\"message\":\"ok\",\"params\":{\"keyword\":\"Thủ Đức\",\"date\":\"2026-07-15\",\"startTime\":\"14:00\"}}", 0, 0, 0));

        StadiumResponse searchRes = new StadiumResponse();
        searchRes.setStadiumId(99);
        searchRes.setStadiumName("Sân Thủ Đức 1");
        lenient().when(publicStadiumService.searchStadiums(any())).thenReturn(
                PageResponse.<StadiumResponse>builder().content(List.of(searchRes)).build());

        AiChatTurnResponse res1 = aiChatService.handleChat(request("tìm sân thủ đức"), userPrincipal, "u:2");
        assertThat(res1.getIntent()).isEqualTo("search_stadiums");

        // Bước 2: Get Slots
        String contextJson = "{\"lastShownStadiumIds\":[99]}";
        lenient().when(redisTemplate.opsForValue().get("ai_ctx:u:2")).thenReturn(contextJson);
        
        Stadium stadium = new Stadium();
        stadium.setStadiumId(99);
        stadium.setStadiumName("Sân Thủ Đức 1");
        stadium.setNodeType(StadiumNodeType.COURT);
        stadium.setStadiumStatus(StadiumStatus.AVAILABLE);

        lenient().when(stadiumRepository.findAllById(List.of(99))).thenReturn(List.of(stadium));
        lenient().when(stadiumRepository.findById(99)).thenReturn(Optional.of(stadium));

        com.sportvenue.dto.response.TimeSlotResponse slotRes = new com.sportvenue.dto.response.TimeSlotResponse();
        slotRes.setSlotId(1234);
        slotRes.setStartTime(java.time.LocalTime.of(14, 0));
        slotRes.setAvailable(true);
        slotRes.setPricePerSlot(java.math.BigDecimal.valueOf(100000));
        lenient().when(bookingService.getSlotsByDate(any(), any())).thenReturn(List.of(slotRes));

        AiChatTurnResponse res2 = aiChatService.handleChat(request("xem giờ trống ngày 15/7"), userPrincipal, "u:2");
        assertThat(res2.getIntent()).isEqualTo("get_slots");

        // Bước 3: Book
        String contextJson2 = "{\"lastShownStadiumIds\":[99], \"lastShownSlotIds\":[1234], \"currentStadiumId\": 99}";
        lenient().when(redisTemplate.opsForValue().get("ai_ctx:u:2")).thenReturn(contextJson2);
        
        AiChatTurnResponse res3 = aiChatService.handleChat(request("đặt lúc 14:00"), userPrincipal, "u:2");
        assertThat(res3.getIntent()).isEqualTo("confirm_booking");
        assertThat(res3.getDraftBooking()).isNotNull();
        assertThat(res3.getDraftBooking().getStartTime()).isEqualTo("14:00");
    }

    @Test
    void testSlotFilling_Search_BookNoTime_ProvideTime() {
        User mockUser = new User();
        mockUser.setUserId(1);
        mockUser.setEmail("user@test.com");
        UserPrincipal userPrincipal = new UserPrincipal(mockUser);

        // Turn 1: Search
        lenient().when(groqClient.chatJson(any(), anyString(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"search_stadiums\",\"message\":\"ok\",\"params\":{\"keyword\":\"Thủ Đức\"}}", 0, 0, 0))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"create_booking\",\"message\":\"ok\",\"params\":{\"keyword\":\"Thủ Đức\",\"date\":\"2026-07-16\"}}", 0, 0, 0))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"create_booking\",\"message\":\"ok\",\"params\":{\"startTime\":\"14:00\"}}", 0, 0, 0));

        StadiumResponse searchRes = new StadiumResponse();
        searchRes.setStadiumId(99);
        searchRes.setStadiumName("Sân Thủ Đức 1");
        lenient().when(publicStadiumService.searchStadiums(any())).thenReturn(
                PageResponse.<StadiumResponse>builder().content(List.of(searchRes)).build());

        AiChatTurnResponse res1 = aiChatService.handleChat(request("tìm sân thủ đức"), userPrincipal, "u:3");
        assertThat(res1.getIntent()).isEqualTo("search_stadiums");

        // Turn 2: Book but without time
        // Giả lập Redis đang lưu lastShownStadiumIds = [99] (do Search vừa xong lưu lại)
        String contextJson1 = "{\"lastShownStadiumIds\":[99]}";
        lenient().when(redisTemplate.opsForValue().get("ai_ctx:u:3")).thenReturn(contextJson1);
        
        Stadium stadium = new Stadium();
        stadium.setStadiumId(99);
        stadium.setStadiumName("Sân Thủ Đức 1");
        stadium.setNodeType(StadiumNodeType.COURT);
        stadium.setStadiumStatus(StadiumStatus.AVAILABLE);

        lenient().when(stadiumRepository.findAllById(List.of(99))).thenReturn(List.of(stadium));
        lenient().when(stadiumRepository.findById(99)).thenReturn(Optional.of(stadium));

        com.sportvenue.dto.response.TimeSlotResponse slotRes = new com.sportvenue.dto.response.TimeSlotResponse();
        slotRes.setSlotId(1234);
        slotRes.setStartTime(java.time.LocalTime.of(14, 0));
        slotRes.setAvailable(true);
        slotRes.setPricePerSlot(java.math.BigDecimal.valueOf(100000));
        lenient().when(bookingService.getSlotsByDate(any(), any())).thenReturn(List.of(slotRes));

        AiChatTurnResponse res2 = aiChatService.handleChat(request("đặt sân thủ đức vào ngày 16/7"), userPrincipal, "u:3");
        // Hàm booking handler sẽ nhận ra thiếu time và hỏi lại, đồng thời lưu pending state (stadiumId=99, date=2026-07-16)
        assertThat(res2.getIntent()).isEqualTo("create_booking");
        assertThat(res2.getMessage()).contains("Chưa xác định được khung giờ");

        // Turn 3: User provide time only
        // Giả lập Redis lưu pending state từ lượt trước (Lưu ý: Mock cần tái hiện lại đúng trạng thái đã serialize)
        String contextJson2 = "{\"lastShownStadiumIds\":[99], \"pendingAction\": {\"intent\": \"create_booking\", \"data\": {\"stadiumId\": 99, \"date\": \"2026-07-16\"}, \"missingField\": \"slot\"}}";
        lenient().when(redisTemplate.opsForValue().get("ai_ctx:u:3")).thenReturn(contextJson2);

        AiChatTurnResponse res3 = aiChatService.handleChat(request("2 giờ chiều"), userPrincipal, "u:3");
        assertThat(res3.getIntent()).isEqualTo("confirm_booking");
        assertThat(res3.getDraftBooking()).isNotNull();
        assertThat(res3.getDraftBooking().getStartTime()).isEqualTo("14:00");
        assertThat(res3.getDraftBooking().getDate()).isEqualTo("16/07/2026");
        assertThat(res3.getDraftBooking().getStadiumId()).isEqualTo(99);
    }

    @Test
    void testSearchThenBookDirectly_ContextResolved() {
        User mockUser = new User();
        mockUser.setUserId(1);
        mockUser.setEmail("user@test.com");
        UserPrincipal userPrincipal = new UserPrincipal(mockUser);

        // Lượt 1: Search
        lenient().when(groqClient.chatJson(any(), anyString(), any(), any()))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"search_stadiums\",\"message\":\"Tìm thấy sân\",\"params\":{\"keyword\":\"Chi Lăng\"}}",
                        0, 0, 0))
                .thenReturn(new GroqClient.GroqResult(
                        "{\"intent\":\"create_booking\",\"message\":\"Ok đặt\",\"params\":{\"keyword\":\"Chi Lăng\",\"date\":\"2026-07-15\"}}",
                        0, 0, 0));

        StadiumResponse searchRes = new StadiumResponse();
        searchRes.setStadiumId(233);
        searchRes.setStadiumName("Sân vận động Chi Lăng - Sân 1");
        lenient().when(publicStadiumService.searchStadiums(any())).thenReturn(
                PageResponse.<StadiumResponse>builder().content(List.of(searchRes)).build());

        aiChatService.handleChat(request("cần tìm sân bóng đá chi lăng"), userPrincipal, "u:1");

        // Lượt 2: Book sân
        String contextJson = "{\"lastShownStadiumIds\":[233]}";
        lenient().when(redisTemplate.opsForValue().get("ai_ctx:u:1")).thenReturn(contextJson);

        Stadium stadium233 = new Stadium();
        stadium233.setStadiumId(233);
        stadium233.setStadiumName("Sân 1");
        stadium233.setNodeType(StadiumNodeType.COURT);
        stadium233.setStadiumStatus(StadiumStatus.AVAILABLE);

        Stadium parentFacility = new Stadium();
        parentFacility.setStadiumId(232);
        parentFacility.setStadiumName("Sân vận động Chi Lăng");
        stadium233.setParentStadium(parentFacility);

        lenient().when(stadiumRepository.findAllById(List.of(233))).thenReturn(List.of(stadium233));
        lenient().when(stadiumRepository.findById(233)).thenReturn(Optional.of(stadium233));

        AiChatTurnResponse response2 = aiChatService.handleChat(request("đặt sân bóng đá chi Lăng"), userPrincipal, "u:1");

        assertThat(response2.getMessage()).doesNotContain("Chưa xác định được sân");
        assertThat(response2.getMessage()).contains("Chưa xác định được khung giờ");
    }
}
