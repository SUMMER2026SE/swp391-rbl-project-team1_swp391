package com.sportvenue.service.ai.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportvenue.dto.request.StadiumSearchRequest;
import com.sportvenue.dto.response.AiChatTurnResponse;
import com.sportvenue.dto.response.PageResponse;
import com.sportvenue.dto.response.StadiumResponse;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.enums.StadiumNodeType;
import com.sportvenue.entity.enums.StadiumStatus;
import com.sportvenue.mapper.StadiumMapper;
import com.sportvenue.repository.SportTypeRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.service.MaintenanceScheduleService;
import com.sportvenue.service.PublicStadiumService;
import com.sportvenue.service.ai.AiConversationContextService;
import com.sportvenue.util.location.VietnamLocationResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test cho phần mới của Phase 2: progressive relaxation (bỏ khu vực -> bỏ giá -> bỏ ngày/giờ)
 * khi search rỗng, và lưu lastShownResults sau khi có kết quả cuối cùng — xem
 * docs/ai_chatbot_rebuild_plan.md mục 1/6.2.
 */
@ExtendWith(MockitoExtension.class)
class StadiumSearchHandlerTest {

    @Mock
    private PublicStadiumService publicStadiumService;
    @Mock
    private SportTypeRepository sportTypeRepository;
    @Mock
    private StadiumRepository stadiumRepository;
    @Mock
    private StadiumMapper stadiumMapper;
    @Mock
    private MaintenanceScheduleService maintenanceScheduleService;
    @Mock
    private AiConversationContextService conversationContextService;

    private final VietnamLocationResolver locationResolver = new VietnamLocationResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private StadiumSearchHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StadiumSearchHandler(publicStadiumService, sportTypeRepository, stadiumRepository,
                stadiumMapper, locationResolver, maintenanceScheduleService, conversationContextService);
        // postProcessSearchResults tra courtById để ghép tên facility/lọc bảo trì — trả rỗng để
        // trở thành no-op, tập trung test đúng vào logic relaxation. lenient() vì test rỗng-toàn-bộ
        // (allRelaxationLevelsEmpty) không bao giờ chạm tới postProcessSearchResults với list
        // non-empty nên không dùng đến stub này — strict stubbing sẽ báo lỗi nếu không có lenient.
        lenient().when(stadiumRepository.findCourtsForAiToolByIds(anyList())).thenReturn(List.of());
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private static boolean hasLocation(StadiumSearchRequest r) {
        return r != null && (r.getDistrict() != null || r.getProvince() != null || r.getAddress() != null);
    }

    @Test
    void emptyStrictSearch_relaxesLocationFirst_thenPrice_stopsAtFirstNonEmpty() throws Exception {
        StadiumResponse relaxedByPrice = StadiumResponse.builder().stadiumId(201).stadiumName("Sân A").build();

        when(publicStadiumService.searchStadiums(argThat(StadiumSearchHandlerTest::hasLocation)))
                .thenReturn(PageResponse.<StadiumResponse>builder().content(List.of()).build());
        when(publicStadiumService.searchStadiums(argThat(r -> r != null && !hasLocation(r) && r.getMinPrice() != null)))
                .thenReturn(PageResponse.<StadiumResponse>builder().content(List.of()).build());
        when(publicStadiumService.searchStadiums(argThat(r -> r != null && !hasLocation(r) && r.getMinPrice() == null)))
                .thenReturn(PageResponse.<StadiumResponse>builder().content(List.of(relaxedByPrice)).build());

        AiChatTurnResponse response = handler.handle(
                json("{\"district\":\"Quận 9\",\"minPrice\":100000,\"maxPrice\":200000}"), "...", "s:test");

        assertThat(response.getStadiums()).containsExactly(relaxedByPrice);
        assertThat(response.getMessage()).contains("khoảng giá");
        verify(conversationContextService).saveLastShownStadiums("s:test", List.of(201));
    }

    @Test
    void allRelaxationLevelsEmpty_returnsNotFoundMessage_withoutCrashing() throws Exception {
        when(publicStadiumService.searchStadiums(any()))
                .thenReturn(PageResponse.<StadiumResponse>builder().content(List.of()).build());

        AiChatTurnResponse response = handler.handle(
                json("{\"district\":\"Quận 9\",\"minPrice\":100000}"), "...", "s:test");

        assertThat(response.getStadiums()).isEmpty();
        assertThat(response.getMessage()).contains("Chưa tìm thấy sân phù hợp");
    }

    @Test
    void districtWithWrongDiacritics_stillNormalizes() throws Exception {
        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of(StadiumResponse.builder().stadiumId(1).build()))
                .pageNumber(0).pageSize(5).totalElements(1).totalPages(1).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);

        handler.handle(json("{\"district\":\"Thù Đùc\"}"), "...", "s:test");

        ArgumentCaptor<StadiumSearchRequest> captor = ArgumentCaptor.forClass(StadiumSearchRequest.class);
        verify(publicStadiumService).searchStadiums(captor.capture());
        assertEquals("Thủ Đức", captor.getValue().getDistrict());
    }

    @Test
    void wrongDiacriticsSportName_stillResolves() throws Exception {
        SportType football = SportType.builder().sportTypeId(1).sportName("Bóng đá").sportCode("FOOTBALL").build();
        when(sportTypeRepository.findAll()).thenReturn(List.of(football));

        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);

        handler.handle(json("{\"sportName\":\"Bông đá\"}"), "...", "s:test");

        ArgumentCaptor<StadiumSearchRequest> captor = ArgumentCaptor.forClass(StadiumSearchRequest.class);
        verify(publicStadiumService).searchStadiums(captor.capture());
        assertEquals(1, captor.getValue().getSportTypeId());
    }

    @Test
    void colloquialAlias_daBanh_resolvesFootball() throws Exception {
        SportType football = SportType.builder().sportTypeId(1).sportName("Bóng đá").sportCode("FOOTBALL").build();
        when(sportTypeRepository.findAll()).thenReturn(List.of(football));

        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);

        handler.handle(json("{\"sportName\":\"đá banh\"}"), "...", "s:test");

        ArgumentCaptor<StadiumSearchRequest> captor = ArgumentCaptor.forClass(StadiumSearchRequest.class);
        verify(publicStadiumService).searchStadiums(captor.capture());
        assertEquals(1, captor.getValue().getSportTypeId());
    }

    @Test
    void emptyResult_fallsBackToParentFacilityName() throws Exception {
        PageResponse<StadiumResponse> emptyResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(emptyResponse);

        Stadium court = Stadium.builder().stadiumId(219).stadiumName("Sân 1").nodeType(StadiumNodeType.COURT).build();
        when(stadiumRepository.findCourtsByParentFacilityNameKeyword("Sân vận động Cẩm Lệ", null)).thenReturn(List.of(court));
        when(stadiumRepository.findCourtsForAiToolByIds(List.of(219))).thenReturn(List.of(court));
        when(stadiumMapper.toResponse(court)).thenReturn(StadiumResponse.builder().stadiumId(219).stadiumName("Sân 1").build());

        AiChatTurnResponse response = handler.handle(json("{\"keyword\":\"Sân vận động Cẩm Lệ\"}"), "...", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
        assertEquals(219, response.getStadiums().get(0).getStadiumId());
    }

    @Test
    void emptyResult_diacriticInsensitiveFallback() throws Exception {
        PageResponse<StadiumResponse> emptyResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(emptyResponse);
        when(stadiumRepository.findCourtsByParentFacilityNameKeyword(anyString(), any())).thenReturn(List.of());

        StadiumRepository.CourtFacilityNameProjection projection = mock(StadiumRepository.CourtFacilityNameProjection.class);
        when(projection.getStadiumId()).thenReturn(219);
        when(projection.getFacilityName()).thenReturn("Sân vận động Cẩm Lệ");
        when(stadiumRepository.findAllCourtFacilityNames()).thenReturn(List.of(projection));

        Stadium court = Stadium.builder().stadiumId(219).stadiumName("Sân 1").nodeType(StadiumNodeType.COURT).build();
        when(stadiumRepository.findCourtsForAiToolByIds(List.of(219))).thenReturn(List.of(court));
        when(stadiumMapper.toResponse(court)).thenReturn(StadiumResponse.builder().stadiumId(219).build());

        AiChatTurnResponse response = handler.handle(json("{\"keyword\":\"San van dong Cam Le\"}"), "...", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
    }

    @Test
    void unknownSportName_returnsAvailableSportsList() throws Exception {
        SportType football = SportType.builder().sportTypeId(1).sportName("Bóng đá").sportCode("FOOTBALL").build();
        when(sportTypeRepository.findAll()).thenReturn(List.of(football));

        AiChatTurnResponse response = handler.handle(json("{\"sportName\":\"Bóng bầu dục\"}"), "...", "s:test");

        assertThat(response.getMessage()).contains("Không tìm thấy môn thể thao");
        assertThat(response.getMessage()).contains("Bóng đá");
        assertThat(response.getStadiums()).isNull();
    }

    @Test
    void ratingSort_mapsToAverageRatingDesc() throws Exception {
        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);

        // keyword bat buoc phai co de khong bi chan boi guardrail "khong co sport/district/keyword"
        handler.handle(json("{\"keyword\":\"Cẩm Lệ\",\"sortBy\":\"rating\"}"), "...", "s:test");

        ArgumentCaptor<StadiumSearchRequest> captor = ArgumentCaptor.forClass(StadiumSearchRequest.class);
        verify(publicStadiumService).searchStadiums(captor.capture());
        assertEquals("averageRating", captor.getValue().getSortBy());
        assertEquals("DESC", captor.getValue().getSortDirection());
    }

    @Test
    void noSportDistrictOrKeyword_returnsNeedMoreInfo_withoutCallingSearch() throws Exception {
        AiChatTurnResponse response = handler.handle(json("{}"), "...", "s:test");

        assertThat(response.getIntent()).isEqualTo("need_more_info");
        assertThat(response.getStadiums()).isNull();
        org.mockito.Mockito.verifyNoInteractions(publicStadiumService);
    }

    @Test
    void enrichesCourtNameWithParentFacilityName() throws Exception {
        Stadium parent = Stadium.builder().stadiumId(100).stadiumName("Sân vận động Cẩm Lệ").nodeType(StadiumNodeType.FACILITY).build();
        Stadium court = Stadium.builder().stadiumId(219).stadiumName("Sân 1").nodeType(StadiumNodeType.COURT).parentStadium(parent).build();

        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of(StadiumResponse.builder().stadiumId(219).stadiumName("Sân 1").build()))
                .pageNumber(0).pageSize(5).totalElements(1).totalPages(1).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);
        when(stadiumRepository.findCourtsForAiToolByIds(List.of(219))).thenReturn(List.of(court));

        AiChatTurnResponse response = handler.handle(json("{\"keyword\":\"Cẩm Lệ\"}"), "lời thoại", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
        assertEquals("Sân vận động Cẩm Lệ - Sân 1", response.getStadiums().get(0).getStadiumName());
    }

    @Test
    void dropsStadiumUnderMaintenanceSchedule() throws Exception {
        Stadium normalCourt = Stadium.builder().stadiumId(1).stadiumName("Sân A").nodeType(StadiumNodeType.COURT).build();
        Stadium maintainedCourt = Stadium.builder().stadiumId(2).stadiumName("Sân B").nodeType(StadiumNodeType.COURT).build();

        PageResponse<StadiumResponse> pageResponse = PageResponse.<StadiumResponse>builder()
                .content(List.of(
                        StadiumResponse.builder().stadiumId(1).stadiumName("Sân A").build(),
                        StadiumResponse.builder().stadiumId(2).stadiumName("Sân B").build()))
                .pageNumber(0).pageSize(5).totalElements(2).totalPages(1).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(pageResponse);
        when(stadiumRepository.findCourtsForAiToolByIds(List.of(1, 2))).thenReturn(List.of(normalCourt, maintainedCourt));
        when(maintenanceScheduleService.isStadiumUnderMaintenance(eq(normalCourt), any(LocalDate.class))).thenReturn(false);
        when(maintenanceScheduleService.isStadiumUnderMaintenance(eq(maintainedCourt), any(LocalDate.class))).thenReturn(true);

        AiChatTurnResponse response = handler.handle(json("{\"district\":\"Quận 9\"}"), "...", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
        assertEquals(1, response.getStadiums().get(0).getStadiumId());
    }

    @Test
    void noisyKeyword_retriesWithoutKeyword() throws Exception {
        PageResponse<StadiumResponse> empty = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        PageResponse<StadiumResponse> found = PageResponse.<StadiumResponse>builder()
                .content(List.of(StadiumResponse.builder().stadiumId(7).stadiumName("Sân Thủ Đức").build()))
                .pageNumber(0).pageSize(5).totalElements(1).totalPages(1).last(true).build();

        List<String> capturedKeywords = new ArrayList<>();
        when(publicStadiumService.searchStadiums(any())).thenAnswer(invocation -> {
            StadiumSearchRequest req = invocation.getArgument(0);
            capturedKeywords.add(req.getKeyword());
            return capturedKeywords.size() == 1 ? empty : found;
        });
        when(stadiumRepository.findCourtsByParentFacilityNameKeyword(anyString(), any())).thenReturn(List.of());
        when(stadiumRepository.findAllCourtFacilityNames()).thenReturn(List.of());

        AiChatTurnResponse response = handler.handle(
                json("{\"keyword\":\"sân bóng thủ đức\",\"district\":\"Thủ Đức\"}"), "...", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
        assertEquals(7, response.getStadiums().get(0).getStadiumId());

        ArgumentCaptor<StadiumSearchRequest> captor = ArgumentCaptor.forClass(StadiumSearchRequest.class);
        verify(publicStadiumService, org.mockito.Mockito.times(2)).searchStadiums(captor.capture());
        assertEquals("sân bóng thủ đức", capturedKeywords.get(0));
        assertEquals(null, capturedKeywords.get(1));
        assertEquals("Thủ Đức", captor.getAllValues().get(1).getDistrict());
    }

    @Test
    void tokenFallback_matchesFacilityWithExtraWords() throws Exception {
        PageResponse<StadiumResponse> empty = PageResponse.<StadiumResponse>builder()
                .content(List.of()).pageNumber(0).pageSize(5).totalElements(0).totalPages(0).last(true).build();
        when(publicStadiumService.searchStadiums(any())).thenReturn(empty);
        when(stadiumRepository.findCourtsByParentFacilityNameKeyword(anyString(), any())).thenReturn(List.of());

        StadiumRepository.CourtFacilityNameProjection projection = mock(StadiumRepository.CourtFacilityNameProjection.class);
        when(projection.getStadiumId()).thenReturn(300);
        when(projection.getFacilityName()).thenReturn("Sân bóng đá Vĩnh Hoàng");
        when(stadiumRepository.findAllCourtFacilityNames()).thenReturn(List.of(projection));

        Stadium court = Stadium.builder().stadiumId(300).stadiumName("Sân 1").nodeType(StadiumNodeType.COURT).build();
        when(stadiumRepository.findCourtsForAiToolByIds(List.of(300))).thenReturn(List.of(court));
        when(stadiumMapper.toResponse(court)).thenReturn(StadiumResponse.builder().stadiumId(300).stadiumName("Sân 1").build());

        AiChatTurnResponse response = handler.handle(json("{\"keyword\":\"San bong Vinh Hoang\"}"), "...", "s:test");

        assertThat(response.getStadiums()).hasSize(1);
        assertEquals(300, response.getStadiums().get(0).getStadiumId());
    }
}

