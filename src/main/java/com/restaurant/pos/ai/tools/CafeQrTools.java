package com.restaurant.pos.ai.tools;

import org.springframework.beans.factory.annotation.Autowired;
import com.restaurant.pos.ai.backend.BusinessInfoService;
import com.restaurant.pos.ai.backend.BusinessInfoService.BusinessInfo;
import com.restaurant.pos.ai.backend.CafeQrApiClient;
import com.restaurant.pos.ai.config.AiProperties;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The v1 read-only tool catalogue. Every tool calls an existing cafe-qr-backend GET endpoint with the owner's
 * token, trims the response to what is useful, caps row counts and never returns customer contact details.
 * Tax data is passed through as recorded by CafeQR; no tax rules are applied here (they differ by country).
 */
@Component
public class CafeQrTools {

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CafeQrApiClient api;
    private final BusinessInfoService businessInfo;
    private final AiProperties.Limits limits;
    private final Clock clock;
    private final List<CafeQrTool> tools;

    @Autowired
    public CafeQrTools(CafeQrApiClient api, BusinessInfoService businessInfo, AiProperties properties) {
        this(api, businessInfo, properties, Clock.systemUTC());
    }

    CafeQrTools(CafeQrApiClient api, BusinessInfoService businessInfo, AiProperties properties, Clock clock) {
        this.api = api;
        this.businessInfo = businessInfo;
        this.limits = properties.limits();
        this.clock = clock;
        this.tools = List.of(
                tool("get_business_info", "Café details",
                        "Café name, currency, timezone, country and today's date. Call this first when you need the currency or today's date.",
                        Schema.object().build(), this::businessInfo),
                tool("list_branches", "Branches",
                        "Lists the café's branches (outlets) with their IDs, for use as branch_id in other tools.",
                        Schema.object().build(), this::branches),
                tool("get_sales_summary", "Sales summary",
                        "Total orders, revenue, average order value, items sold, tax, discounts and grand total for a date range.",
                        Schema.object().dateRange().branch().build(), this::salesSummary),
                tool("get_top_items", "Top selling items",
                        "Best (or worst) selling menu items for a date range, by revenue or quantity.",
                        Schema.object().dateRange().branch()
                                .integer("limit", "How many items to return (default 10).", 1, 50)
                                .enumeration("sort_by", "Rank by revenue (default) or quantity.", List.of("revenue", "quantity"))
                                .enumeration("order", "'top' for best sellers (default), 'bottom' for slowest sellers.", List.of("top", "bottom"))
                                .build(), this::topItems),
                tool("get_payment_breakdown", "Payment methods",
                        "Sales split by payment method (cash, card, UPI, credit...) with counts, amounts and share of total.",
                        Schema.object().dateRange().branch().build(), this::paymentBreakdown),
                tool("get_hourly_sales", "Sales by hour",
                        "Orders and sales per hour of day for a date range, to find peak and quiet hours.",
                        Schema.object().dateRange().branch().build(), this::hourlySales),
                tool("get_tax_summary", "Tax summary",
                        "Tax collected grouped by tax rate, exactly as recorded in CafeQR, for a date range.",
                        Schema.object().dateRange().branch().build(), this::taxSummary),
                tool("get_profit_loss", "Profit and loss",
                        "Gross sales, discounts, net sales, purchases (COGS), expenses and net profit for a date range.",
                        Schema.object().dateRange().branch().build(), this::profitLoss),
                tool("list_orders", "Orders",
                        "Recent sale orders in a date range with number, time, status, payment, table, total and items. No customer contact details.",
                        Schema.object().dateRange().branch()
                                .string("status", "Optional order status filter, e.g. COMPLETED, PAID, DRAFT, CANCELLED.")
                                .integer("limit", "Max orders to return (default 20).", 1, 50)
                                .build(), this::orders),
                tool("get_stock_levels", "Stock levels",
                        "Current stock quantity per product. By default only items at or below the low-stock threshold, lowest first. Needs the Inventory module.",
                        Schema.object().branch()
                                .integer("low_stock_threshold", "Quantity at or below which an item counts as low (default 5).", 0, 100000)
                                .bool("only_low", "true (default) = only low-stock items; false = all items.")
                                .string("search", "Optional product name filter.")
                                .build(), this::stockLevels),
                tool("list_menu_items", "Menu items",
                        "Menu/products with category, price and availability. Supports a name search.",
                        Schema.object()
                                .string("search", "Optional name search.")
                                .string("category", "Optional category name filter.")
                                .bool("available_only", "true = only items currently available for sale.")
                                .build(), this::menuItems),
                tool("get_table_status", "Tables",
                        "Dine-in tables with their current status (AVAILABLE, OCCUPIED, RESERVED, MAINTENANCE) and a count per status.",
                        Schema.object().build(), this::tableStatus));
    }

    public List<CafeQrTool> all() {
        return tools;
    }

    private static CafeQrTool tool(String name, String title, String description, Map<String, Object> schema,
                                   java.util.function.BiFunction<OwnerPrincipal, ToolArgs, Object> handler) {
        return new CafeQrTool(name, title, description, schema, handler);
    }

    // ---- tools -------------------------------------------------------------------------------------------

    private Object businessInfo(OwnerPrincipal owner, ToolArgs args) {
        BusinessInfo info = businessInfo.get(owner);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", info.name());
        m.put("currency", info.currency());
        m.put("timezone", info.zone().getId());
        m.put("country", info.country());
        m.put("today", LocalDate.now(clock.withZone(info.zone())).toString());
        return m;
    }

    private Object branches(OwnerPrincipal owner, ToolArgs args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode o : api.get(owner, "/api/v1/organizations", Map.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branch_id", text(o, "id"));
            m.put("name", text(o, "name"));
            m.put("code", text(o, "branchCode"));
            m.put("is_current", owner.orgId() != null && owner.orgId().toString().equals(text(o, "id")));
            rows.add(m);
        }
        return Map.of("branches", rows);
    }

    private Object salesSummary(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/sales-summary");
        JsonNode d = r.data;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_orders", d.path("totalOrders").asLong(0));
        summary.put("items_sold", d.path("itemsSold").asLong(0));
        summary.put("revenue", num(d, "totalRevenue"));
        summary.put("average_order_value", num(d, "avgOrderValue"));
        summary.put("discounts", num(d, "totalDiscount"));
        summary.put("tax", num(d, "totalTax"));
        summary.put("round_off", num(d, "totalRoundOff"));
        summary.put("grand_total", num(d, "grandTotal"));
        return r.wrap("summary", summary);
    }

    private Object topItems(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/item-wise");
        boolean byQty = "quantity".equalsIgnoreCase(args.string("sort_by"));
        boolean bottom = "bottom".equalsIgnoreCase(args.string("order"));
        int limit = args.integer("limit", 10, 1, Math.min(50, limits.maxRows()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode i : r.data) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", text(i, "productName"));
            m.put("category", text(i, "categoryName"));
            m.put("quantity_sold", num(i, "quantitySold"));
            m.put("revenue", num(i, "revenue"));
            items.add(m);
        }
        Comparator<Map<String, Object>> cmp = Comparator.comparing(
                m -> (BigDecimal) m.get(byQty ? "quantity_sold" : "revenue"),
                Comparator.nullsFirst(Comparator.naturalOrder()));
        items.sort(bottom ? cmp : cmp.reversed());
        Map<String, Object> out = r.wrap("items", items.subList(0, Math.min(limit, items.size())));
        out.put("ranked_by", byQty ? "quantity" : "revenue");
        out.put("distinct_items_sold", items.size());
        return out;
    }

    private Object paymentBreakdown(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/payment-breakdown");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode p : r.data) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", text(p, "paymentMethod"));
            m.put("orders", p.path("orderCount").asLong(0));
            m.put("amount", num(p, "totalAmount"));
            m.put("percentage", num(p, "percentage"));
            rows.add(m);
        }
        return r.wrap("payment_methods", rows);
    }

    private Object hourlySales(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/hourly");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode h : r.data) {
            if (h.path("orderCount").asLong(0) == 0) {
                continue; // skip empty hours to keep the answer small
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", text(h, "hourLabel") != null ? text(h, "hourLabel") : h.path("hour").asInt());
            m.put("orders", h.path("orderCount").asLong(0));
            m.put("amount", num(h, "totalAmount"));
            rows.add(m);
        }
        return r.wrap("hours_with_sales", rows);
    }

    private Object taxSummary(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/tax-summary");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode t : r.data) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tax_rate_percent", num(t, "taxRate"));
            m.put("taxable_amount", num(t, "taxableAmount"));
            // Component columns exactly as CafeQR records them; zero/absent ones are dropped.
            putIfNonZero(m, "cgst", num(t, "cgst"));
            putIfNonZero(m, "sgst", num(t, "sgst"));
            m.put("total_tax", num(t, "totalTax"));
            m.put("lines", t.path("lineCount").asLong(0));
            rows.add(m);
        }
        Map<String, Object> out = r.wrap("by_rate", rows);
        out.put("note", "Figures as recorded in CafeQR. For filing, use the official tax reports in the dashboard and your accountant.");
        return out;
    }

    private Object profitLoss(OwnerPrincipal owner, ToolArgs args) {
        Report r = report(owner, args, "/api/v1/reports/profit-loss");
        JsonNode d = r.data;
        Map<String, Object> pl = new LinkedHashMap<>();
        for (String[] f : new String[][]{
                {"gross_sales", "grossSales"}, {"discounts", "discounts"}, {"net_sales", "netSales"},
                {"output_tax", "totalTax"}, {"input_tax", "inputTax"}, {"purchases_cogs", "cogsPurchases"},
                {"operating_expenses", "operatingExpenses"}, {"total_expenses", "totalExpenses"},
                {"net_profit", "netProfit"}, {"credit_outstanding", "creditOutstanding"},
                {"net_cash_profit", "netCashProfit"}, {"cash_after_expenses", "cashCollectedAfterExpenses"}}) {
            pl.put(f[0], num(d, f[1]));
        }
        pl.put("basis", text(d, "basis"));
        return r.wrap("profit_and_loss", pl);
    }

    private Object orders(OwnerPrincipal owner, ToolArgs args) {
        BusinessInfo info = businessInfo.get(owner);
        DateRange range = DateRange.of(args, info.zone(), limits.maxRangeDays(), clock);
        int limit = args.integer("limit", 20, 1, Math.min(50, limits.maxRows()));
        Map<String, Object> q = new HashMap<>();
        q.put("fromDate", range.fromInstant());
        q.put("toDate", range.toInstant());
        q.put("status", args.string("status"));
        q.put("orgId", args.uuid("branch_id"));
        q.put("page", 0);
        q.put("size", limit);
        JsonNode page = api.get(owner, "/api/v1/orders/history", q);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode o : page.path("content")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_no", text(o, "orderNo"));
            m.put("invoice_no", text(o, "invoiceNo"));
            m.put("time", localTime(o, "orderDate", info.zone()));
            m.put("status", text(o, "orderStatus"));
            m.put("payment_status", text(o, "paymentStatus"));
            m.put("payment_method", text(o, "paymentMethod"));
            m.put("type", text(o, "fulfillmentType"));
            m.put("table", text(o, "tableNumber"));
            m.put("grand_total", num(o, "grandTotal"));
            List<String> items = new ArrayList<>();
            for (JsonNode l : o.path("lines")) {
                items.add(plain(num(l, "quantity")) + " x " + text(l, "productName"));
            }
            m.put("items", items);
            // customerName / customerPhone are intentionally not returned (PII).
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", range.describe());
        out.put("currency", info.currency());
        out.put("total_matching_orders", page.path("totalElements").asLong(rows.size()));
        out.put("returned", rows.size());
        out.put("orders", rows);
        return out;
    }

    private Object stockLevels(OwnerPrincipal owner, ToolArgs args) {
        int threshold = args.integer("low_stock_threshold", 5, 0, 100000);
        boolean onlyLow = args.bool("only_low", true);
        String search = lower(args.string("search"));
        Map<String, Object> q = new HashMap<>();
        q.put("orgId", args.uuid("branch_id"));
        JsonNode stock = api.get(owner, "/api/v1/inventory/stock-overview", q);

        Map<String, JsonNode> products = new HashMap<>();
        for (JsonNode p : api.get(owner, "/api/v1/products", Map.of())) {
            products.put(text(p, "id"), p);
        }
        // Sum across warehouses per product/variant.
        Map<String, Object[]> totals = new TreeMap<>();
        for (JsonNode s : stock) {
            JsonNode p = products.get(text(s, "productId"));
            String name = p != null ? text(p, "name") : "Unknown product";
            String variant = text(s, "variantOptionName");
            String label = variant != null ? name + " (" + variant + ")" : name;
            if (search != null && !lower(label).contains(search)) {
                continue;
            }
            Object[] t = totals.computeIfAbsent(label, k -> new Object[]{BigDecimal.ZERO, p != null ? text(p, "uomName") : null,
                    p != null ? text(p, "categoryName") : null});
            t[0] = ((BigDecimal) t[0]).add(num(s, "currentQuantity") == null ? BigDecimal.ZERO : num(s, "currentQuantity"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal limit = BigDecimal.valueOf(threshold);
        totals.forEach((label, t) -> {
            BigDecimal qty = (BigDecimal) t[0];
            if (onlyLow && qty.compareTo(limit) > 0) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", label);
            m.put("category", t[2]);
            m.put("quantity", qty);
            m.put("unit", t[1]);
            rows.add(m);
        });
        rows.sort(Comparator.comparing(m -> (BigDecimal) m.get("quantity")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("low_stock_threshold", threshold);
        out.put("only_low", onlyLow);
        out.put("total_matching", rows.size());
        out.put("items", rows.subList(0, Math.min(limits.maxRows(), rows.size())));
        return out;
    }

    private Object menuItems(OwnerPrincipal owner, ToolArgs args) {
        String category = lower(args.string("category"));
        boolean availableOnly = args.bool("available_only", false);
        Map<String, Object> q = new HashMap<>();
        q.put("page", 0);
        q.put("size", 200);
        q.put("search", args.string("search"));
        JsonNode page = api.get(owner, "/api/v1/products", q);
        JsonNode content = page.has("content") ? page.path("content") : page;
        BusinessInfo info = businessInfo.get(owner);
        List<Map<String, Object>> rows = new ArrayList<>();
        int matching = 0;
        for (JsonNode p : content) {
            if (p.path("isIngredient").asBoolean(false)) {
                continue;
            }
            if (category != null && !lower(String.valueOf(text(p, "categoryName"))).contains(category)) {
                continue;
            }
            boolean available = p.path("isAvailable").asBoolean(p.path("available").asBoolean(true));
            if (availableOnly && !available) {
                continue;
            }
            matching++;
            if (rows.size() >= limits.maxRows()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", text(p, "name"));
            m.put("category", text(p, "categoryName"));
            m.put("price", num(p, "price"));
            m.put("available", available);
            m.put("type", text(p, "productType"));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", info.currency());
        out.put("total_matching", matching);
        out.put("returned", rows.size());
        out.put("items", rows);
        return out;
    }

    private Object tableStatus(OwnerPrincipal owner, ToolArgs args) {
        Map<String, Integer> counts = new TreeMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode t : api.get(owner, "/api/v1/tables/active", Map.of())) {
            String status = text(t, "status") == null ? "UNKNOWN" : text(t, "status");
            counts.merge(status, 1, Integer::sum);
            if (rows.size() < limits.maxRows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", text(t, "tableNumber"));
                m.put("name", text(t, "name"));
                m.put("status", status);
                m.put("seats", t.path("seatingCapacity").isMissingNode() ? null : t.path("seatingCapacity").asInt());
                m.put("floor", text(t, "floor"));
                m.put("section", text(t, "section"));
                rows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count_by_status", counts);
        out.put("tables", rows);
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** Report endpoint called with a date range (and optional branch) in the café's timezone. */
    private Report report(OwnerPrincipal owner, ToolArgs args, String path) {
        BusinessInfo info = businessInfo.get(owner);
        DateRange range = DateRange.of(args, info.zone(), limits.maxRangeDays(), clock);
        UUID branch = args.uuid("branch_id");
        Map<String, Object> q = new HashMap<>();
        q.put("from", range.fromInstant());
        q.put("to", range.toInstant());
        q.put("orgId", branch);
        return new Report(api.get(owner, path, q), range, info.currency());
    }

    private record Report(JsonNode data, DateRange range, String currency) {
        Map<String, Object> wrap(String key, Object value) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", range.describe());
            out.put("currency", currency);
            out.put(key, value);
            return out;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static BigDecimal num(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString();
    }

    private static void putIfNonZero(Map<String, Object> m, String key, BigDecimal value) {
        if (value != null && value.signum() != 0) {
            m.put(key, value);
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static String localTime(JsonNode node, String field, ZoneId zone) {
        String s = text(node, field);
        if (s == null) {
            return null;
        }
        try {
            return LOCAL_TIME.format(Instant.parse(s).atZone(zone));
        } catch (Exception e) {
            return s;
        }
    }
}
