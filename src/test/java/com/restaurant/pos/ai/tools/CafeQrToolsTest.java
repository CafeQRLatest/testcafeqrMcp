package com.restaurant.pos.ai.tools;

import com.restaurant.pos.ai.backend.BusinessInfoService;
import com.restaurant.pos.ai.backend.BusinessInfoService.BusinessInfo;
import com.restaurant.pos.ai.backend.CafeQrApiClient;
import com.restaurant.pos.ai.config.AiProperties;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CafeQrToolsTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final OwnerPrincipal owner = new OwnerPrincipal("tok", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "owner@cafe.test", "ADMIN");
    // 2026-11-06 20:00 UTC = 2026-11-07 01:30 in Asia/Kolkata, so "today" for the café is the 7th.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T20:00:00Z"), ZoneOffset.UTC);

    private CafeQrApiClient api;
    private Map<String, CafeQrTool> tools;

    @BeforeEach
    void setUp() {
        api = mock(CafeQrApiClient.class);
        BusinessInfoService info = mock(BusinessInfoService.class);
        when(info.get(any())).thenReturn(new BusinessInfo("Test Cafe", "INR", "Asia/Kolkata", "IN"));
        AiProperties props = new AiProperties(null, null, new AiProperties.Limits(300, 60, 50, 366), null);
        tools = new HashMap<>();
        new CafeQrTools(api, info, props, clock).all().forEach(t -> tools.put(t.name(), t));
    }

    private Object run(String tool, Map<String, Object> args) {
        return tools.get(tool).handler().apply(owner, new ToolArgs(args));
    }

    private JsonNode node(String s) {
        return json.readTree(s);
    }

    @Test
    void catalogueHasTheV1Tools() {
        assertThat(tools.keySet()).containsExactlyInAnyOrder(
                "get_business_info", "list_branches", "get_sales_summary", "get_top_items", "get_payment_breakdown",
                "get_hourly_sales", "get_tax_summary", "get_profit_loss", "list_orders", "get_stock_levels",
                "list_menu_items", "get_table_status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportDatesDefaultToTodayInTheCafesTimezone() {
        when(api.get(eq(owner), eq("/api/v1/reports/sales-summary"), anyMap()))
                .thenReturn(node("{\"totalOrders\":12,\"totalRevenue\":4500.50,\"grandTotal\":4725.53}"));

        Map<String, Object> out = (Map<String, Object>) run("get_sales_summary", Map.of());

        ArgumentCaptor<Map<String, Object>> q = ArgumentCaptor.forClass(Map.class);
        verify(api).get(eq(owner), eq("/api/v1/reports/sales-summary"), q.capture());
        // 2026-11-07 00:00 IST = 2026-11-06T18:30Z ; end of day = 2026-11-07T18:29:59.999Z
        assertThat(q.getValue().get("from")).isEqualTo(Instant.parse("2026-11-06T18:30:00Z"));
        assertThat(q.getValue().get("to")).isEqualTo(Instant.parse("2026-11-07T18:29:59.999Z"));
        assertThat(out.get("currency")).isEqualTo("INR");
        assertThat(((Map<String, Object>) out.get("period")).get("from")).isEqualTo("2026-11-07");
        assertThat(((Map<String, Object>) out.get("summary")).get("total_orders")).isEqualTo(12L);
    }

    @Test
    void rejectsBadDatesAndTooLongRanges() {
        assertThatThrownBy(() -> run("get_sales_summary", Map.of("from", "07/11/2026")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> run("get_sales_summary", Map.of("from", "2026-11-07", "to", "2026-11-01")))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> run("get_sales_summary", Map.of("from", "2024-01-01", "to", "2026-11-01")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("maximum");
    }

    @Test
    @SuppressWarnings("unchecked")
    void topItemsAreRankedAndCapped() {
        when(api.get(eq(owner), eq("/api/v1/reports/item-wise"), anyMap())).thenReturn(node("""
                [{"productName":"Tea","quantitySold":50,"revenue":1000},
                 {"productName":"Coffee","quantitySold":30,"revenue":3000},
                 {"productName":"Cake","quantitySold":5,"revenue":500}]"""));

        Map<String, Object> byRevenue = (Map<String, Object>) run("get_top_items", Map.of("limit", 2));
        List<Map<String, Object>> items = (List<Map<String, Object>>) byRevenue.get("items");
        assertThat(items).extracting(m -> m.get("item")).containsExactly("Coffee", "Tea");

        Map<String, Object> slowest = (Map<String, Object>) run("get_top_items",
                Map.of("sort_by", "quantity", "order", "bottom", "limit", 1));
        assertThat((List<Map<String, Object>>) slowest.get("items")).extracting(m -> m.get("item")).containsExactly("Cake");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordersNeverIncludeCustomerContactDetails() {
        when(api.get(eq(owner), eq("/api/v1/orders/history"), anyMap())).thenReturn(node("""
                {"totalElements":1,"content":[{"orderNo":"S-1","orderDate":"2026-11-06T19:00:00Z",
                  "customerName":"Ravi","customerPhone":"9999999999","grandTotal":250,
                  "lines":[{"productName":"Tea","quantity":2}]}]}"""));

        Map<String, Object> out = (Map<String, Object>) run("list_orders", Map.of());
        String text = out.toString();
        assertThat(text).doesNotContain("Ravi").doesNotContain("9999999999");
        Map<String, Object> order = ((List<Map<String, Object>>) out.get("orders")).get(0);
        assertThat(order.get("time")).isEqualTo("2026-11-07 00:30"); // shown in café time
        assertThat(order.get("items")).isEqualTo(List.of("2 x Tea"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockLevelsJoinProductNamesAndFilterLowStock() {
        String coffee = UUID.randomUUID().toString();
        String milk = UUID.randomUUID().toString();
        when(api.get(eq(owner), eq("/api/v1/inventory/stock-overview"), anyMap())).thenReturn(node(
                "[{\"productId\":\"" + coffee + "\",\"currentQuantity\":40}," +
                " {\"productId\":\"" + milk + "\",\"currentQuantity\":1.5}," +
                " {\"productId\":\"" + milk + "\",\"currentQuantity\":1}]"));
        when(api.get(eq(owner), eq("/api/v1/products"), anyMap())).thenReturn(node(
                "[{\"id\":\"" + coffee + "\",\"name\":\"Coffee beans\",\"uomName\":\"kg\"}," +
                " {\"id\":\"" + milk + "\",\"name\":\"Milk\",\"uomName\":\"L\"}]"));

        Map<String, Object> out = (Map<String, Object>) run("get_stock_levels", Map.of());
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("item")).isEqualTo("Milk");
        assertThat(items.get(0).get("quantity").toString()).isEqualTo("2.5"); // summed across warehouses
    }

    @Test
    void branchIdIsValidatedAndPassedAsOrgId() {
        UUID branch = UUID.randomUUID();
        when(api.get(eq(owner), eq("/api/v1/reports/payment-breakdown"), anyMap())).thenReturn(node("[]"));
        run("get_payment_breakdown", Map.of("branch_id", branch.toString()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> q = ArgumentCaptor.forClass(Map.class);
        verify(api).get(eq(owner), eq("/api/v1/reports/payment-breakdown"), q.capture());
        assertThat(q.getValue().get("orgId")).isEqualTo(branch);

        assertThatThrownBy(() -> run("get_payment_breakdown", Map.of("branch_id", "main")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("list_branches");
    }
}
