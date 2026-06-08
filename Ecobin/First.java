import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class First {
    private static final int PORT = 8080;
    private static Database db;
    private static final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try {
            System.out.println("Initializing Ecobin Waste Management Backend...");
            db = Database.load();

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // Serve frontend SPA
            server.createContext("/", new StaticFileHandler());

            // API Endpoints
            server.createContext("/api/auth/register", new RegisterHandler());
            server.createContext("/api/auth/login", new LoginHandler());
            server.createContext("/api/admin/register-staff", new AdminRegisterStaffHandler());
            server.createContext("/api/schedules", new SchedulesHandler());
            server.createContext("/api/requests", new RequestsHandler());
            server.createContext("/api/payments", new PaymentsHandler());
            server.createContext("/api/payments/redeem", new RedeemHandler());
            server.createContext("/api/workers", new WorkersHandler());
            server.createContext("/api/workers/assign", new WorkersAssignHandler());
            server.createContext("/api/workers/leave", new WorkersLeaveHandler());
            server.createContext("/api/workers/expense", new WorkersExpenseHandler());
            server.createContext("/api/workers/expense/action", new WorkersExpenseActionHandler());
            server.createContext("/api/reports", new ReportsHandler());

            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();

            System.out.println("=================================================");
            System.out.println(" Ecobin Server started successfully!");
            System.out.println(" Local Access URL: http://localhost:" + PORT);
            System.out.println(" Database Location: " + new File("ecobin_db.json").getAbsolutePath());
            System.out.println(" Press Ctrl+C to stop the server.");
            System.out.println("=================================================");
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- JSON Parser ---
    static class JsonParser {
        private final String src;
        private int cursor;

        public JsonParser(String src) {
            this.src = src != null ? src.trim() : "";
        }

        public Object parse() {
            skipWhitespace();
            if (cursor >= src.length())
                return null;
            char c = src.charAt(cursor);
            if (c == '{')
                return parseObject();
            if (c == '[')
                return parseArray();
            if (c == '"')
                return parseString();
            if (c == 't' || c == 'f')
                return parseBoolean();
            if (c == 'n')
                return parseNull();
            if (Character.isDigit(c) || c == '-')
                return parseNumber();
            throw new RuntimeException("Unexpected character at " + cursor + ": " + c);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            cursor++; // skip '{'
            skipWhitespace();
            if (cursor < src.length() && src.charAt(cursor) == '}') {
                cursor++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (cursor >= src.length() || src.charAt(cursor) != '"') {
                    throw new RuntimeException("Expected string key in object at " + cursor);
                }
                String key = parseString();
                skipWhitespace();
                if (cursor >= src.length() || src.charAt(cursor) != ':') {
                    throw new RuntimeException("Expected ':' in object at " + cursor);
                }
                cursor++; // skip ':'
                Object val = parse();
                map.put(key, val);
                skipWhitespace();
                if (cursor < src.length() && src.charAt(cursor) == '}') {
                    cursor++;
                    break;
                }
                if (cursor >= src.length() || src.charAt(cursor) != ',') {
                    throw new RuntimeException("Expected ',' or '}' in object at " + cursor);
                }
                cursor++; // skip ','
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            cursor++; // skip '['
            skipWhitespace();
            if (cursor < src.length() && src.charAt(cursor) == ']') {
                cursor++;
                return list;
            }
            while (true) {
                list.add(parse());
                skipWhitespace();
                if (cursor < src.length() && src.charAt(cursor) == ']') {
                    cursor++;
                    break;
                }
                if (cursor >= src.length() || src.charAt(cursor) != ',') {
                    throw new RuntimeException("Expected ',' or ']' in array at " + cursor);
                }
                cursor++; // skip ','
            }
            return list;
        }

        private String parseString() {
            cursor++; // skip opening '"'
            StringBuilder sb = new StringBuilder();
            while (cursor < src.length()) {
                char c = src.charAt(cursor);
                if (c == '"') {
                    cursor++;
                    return sb.toString();
                }
                if (c == '\\') {
                    cursor++;
                    if (cursor < src.length()) {
                        char next = src.charAt(cursor);
                        if (next == 'n')
                            sb.append('\n');
                        else if (next == 'r')
                            sb.append('\r');
                        else if (next == 't')
                            sb.append('\t');
                        else
                            sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
                cursor++;
            }
            throw new RuntimeException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", cursor)) {
                cursor += 4;
                return true;
            } else if (src.startsWith("false", cursor)) {
                cursor += 5;
                return false;
            }
            throw new RuntimeException("Invalid boolean at " + cursor);
        }

        private Object parseNull() {
            if (src.startsWith("null", cursor)) {
                cursor += 4;
                return null;
            }
            throw new RuntimeException("Invalid null at " + cursor);
        }

        private Number parseNumber() {
            int start = cursor;
            if (src.charAt(cursor) == '-')
                cursor++;
            while (cursor < src.length() && (Character.isDigit(src.charAt(cursor)) || src.charAt(cursor) == '.')) {
                cursor++;
            }
            String val = src.substring(start, cursor);
            if (val.contains(".")) {
                return Double.parseDouble(val);
            } else {
                return Long.parseLong(val);
            }
        }

        private void skipWhitespace() {
            while (cursor < src.length() && Character.isWhitespace(src.charAt(cursor))) {
                cursor++;
            }
        }
    }

    // --- JSON Serializer ---
    static String toJson(Object obj) {
        if (obj == null)
            return "null";
        if (obj instanceof String) {
            return "\"" + escapeString((String) obj) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first)
                    sb.append(",");
                first = false;
                sb.append("\"").append(escapeString(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object el : list) {
                if (!first)
                    sb.append(",");
                first = false;
                sb.append(toJson(el));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeString(obj.toString()) + "\"";
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // --- Helper for HTTP Request/Response ---
    static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    static void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    static boolean handlePreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    static Map<String, Object> checkAuth(HttpExchange exchange, String... requiredRoles) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.trim().isEmpty()) {
            sendResponse(exchange, 401, "{\"error\":\"Missing authorization token\"}");
            return null;
        }
        if (authHeader.startsWith("Bearer ")) {
            authHeader = authHeader.substring(7);
        }
        Map<String, Object> session = sessions.get(authHeader.trim());
        if (session == null) {
            sendResponse(exchange, 401, "{\"error\":\"Invalid or expired session token\"}");
            return null;
        }
        if (requiredRoles.length > 0) {
            String role = (String) session.get("role");
            boolean match = false;
            for (String req : requiredRoles) {
                if (req.equalsIgnoreCase(role)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                sendResponse(exchange, 403, "{\"error\":\"Forbidden: Insufficient privileges\"}");
                return null;
            }
        }
        return session;
    }

    // --- Static File Handler (Serves index.html) ---
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                File htmlFile = new File("index.html");
                if (htmlFile.exists()) {
                    byte[] bytes = Files.readAllBytes(htmlFile.toPath());
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(bytes);
                    os.close();
                } else {
                    String error = "<html><body style='font-family:sans-serif; text-align:center; padding-top:50px;'>"
                            + "<h1>Ecobin Server Running</h1><p>index.html not found. Place it in the directory: "
                            + new File("index.html").getAbsolutePath() + "</p></body></html>";
                    byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(404, bytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(bytes);
                    os.close();
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        }
    }

    // --- API Handlers ---

    // POST /api/auth/register
    static class RegisterHandler implements HttpHandler {
        @SuppressWarnings("unchecked")
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                String userId = (String) req.get("userId");
                String password = (String) req.get("password");
                String name = (String) req.get("name");
                String phone = (String) req.get("phone");
                String email = (String) req.get("email");
                String address = (String) req.get("address");
                String role = (String) req.get("role"); // USER, STAFF, WORKER

                if (userId == null || password == null || name == null || role == null) {
                    sendResponse(exchange, 400,
                            "{\"error\":\"Missing required fields: userId, password, name, role\"}");
                    return;
                }

                role = role.toUpperCase();
                if (!role.equals("USER") && !role.equals("STAFF") && !role.equals("WORKER")) {
                    sendResponse(exchange, 400, "{\"error\":\"Invalid role. Must be USER, STAFF, or WORKER\"}");
                    return;
                }

                synchronized (db) {
                    // Check duplicate
                    for (Map<String, Object> u : db.users) {
                        if (userId.equalsIgnoreCase((String) u.get("userId"))) {
                            sendResponse(exchange, 400, "{\"error\":\"User ID already registered\"}");
                            return;
                        }
                    }

                    Map<String, Object> newUser = new LinkedHashMap<>();
                    newUser.put("userId", userId);
                    newUser.put("password", password);
                    newUser.put("name", name);
                    newUser.put("phone", phone != null ? phone : "");
                    newUser.put("email", email != null ? email : "");
                    newUser.put("address", address != null ? address : "");
                    newUser.put("role", role);

                    if (role.equals("USER")) {
                        newUser.put("rewardPoints", 0.0);
                        // Map user to an area based on address if not explicitly passed
                        String area = (String) req.get("area");
                        if (area == null || area.isEmpty()) {
                            area = detectArea(address);
                        }
                        newUser.put("area", area);
                    } else if (role.equals("WORKER")) {
                        // Backend automatically assigns worker to area
                        String assignedArea = autoAssignWorkerArea(db);
                        newUser.put("area", assignedArea);
                        System.out.println(
                                "Auto-assigned newly registered Worker '" + userId + "' to area: " + assignedArea);
                    } else if (role.equals("STAFF")) {
                        // Office staff can have an area or default
                        newUser.put("area", "Headquarters");
                    }

                    db.users.add(newUser);
                    db.save();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "User registered successfully");
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/auth/login
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                String userId = (String) req.get("userId");
                String password = (String) req.get("password");

                if (userId == null || password == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing userId or password\"}");
                    return;
                }

                Map<String, Object> matchedUser = null;
                if ("admin".equalsIgnoreCase(userId) && "admin123".equals(password)) {
                    // Admin login
                    matchedUser = new LinkedHashMap<>();
                    matchedUser.put("userId", "admin");
                    matchedUser.put("name", "System Administrator");
                    matchedUser.put("role", "ADMIN");
                } else {
                    synchronized (db) {
                        for (Map<String, Object> u : db.users) {
                            if (userId.equalsIgnoreCase((String) u.get("userId"))
                                    && password.equals(u.get("password"))) {
                                matchedUser = u;
                                break;
                            }
                        }
                    }
                }

                if (matchedUser == null) {
                    sendResponse(exchange, 401, "{\"error\":\"Invalid User ID or password\"}");
                    return;
                }

                // Create session
                String token = UUID.randomUUID().toString();
                Map<String, Object> session = new HashMap<>();
                session.put("userId", matchedUser.get("userId"));
                session.put("role", matchedUser.get("role"));
                session.put("name", matchedUser.get("name"));
                session.put("area", matchedUser.get("area"));
                sessions.put(token, session);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("token", token);
                resp.put("userId", matchedUser.get("userId"));
                resp.put("name", matchedUser.get("name"));
                resp.put("role", matchedUser.get("role"));
                resp.put("area", matchedUser.get("area"));
                if (matchedUser.containsKey("rewardPoints")) {
                    resp.put("rewardPoints", matchedUser.get("rewardPoints"));
                }
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/admin/register-staff
    static class AdminRegisterStaffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange, "ADMIN");
            if (session == null)
                return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                String userId = (String) req.get("userId");
                String password = (String) req.get("password");
                String name = (String) req.get("name");
                String phone = (String) req.get("phone");
                String email = (String) req.get("email");
                String address = (String) req.get("address");

                if (userId == null || password == null || name == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing required fields: userId, password, name\"}");
                    return;
                }

                synchronized (db) {
                    for (Map<String, Object> u : db.users) {
                        if (userId.equalsIgnoreCase((String) u.get("userId"))) {
                            sendResponse(exchange, 400, "{\"error\":\"User ID already registered\"}");
                            return;
                        }
                    }

                    Map<String, Object> newStaff = new LinkedHashMap<>();
                    newStaff.put("userId", userId);
                    newStaff.put("password", password);
                    newStaff.put("name", name);
                    newStaff.put("phone", phone != null ? phone : "");
                    newStaff.put("email", email != null ? email : "");
                    newStaff.put("address", address != null ? address : "");
                    newStaff.put("role", "STAFF");
                    newStaff.put("area", "Headquarters");

                    db.users.add(newStaff);
                    db.save();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Office staff registered successfully");
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/schedules
    static class SchedulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange);
            if (session == null)
                return;

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    String role = (String) session.get("role");
                    String userId = (String) session.get("userId");
                    List<Map<String, Object>> result = new ArrayList<>();

                    synchronized (db) {
                        if ("USER".equalsIgnoreCase(role)) {
                            // Find user's area
                            String userArea = "";
                            for (Map<String, Object> u : db.users) {
                                if (userId.equals(u.get("userId"))) {
                                    userArea = (String) u.get("area");
                                    break;
                                }
                            }
                            // Return schedules for this area
                            for (Map<String, Object> s : db.schedules) {
                                if (userArea.equalsIgnoreCase((String) s.get("area"))) {
                                    result.add(s);
                                }
                            }
                        } else if ("WORKER".equalsIgnoreCase(role)) {
                            // Return schedules assigned to this worker
                            for (Map<String, Object> s : db.schedules) {
                                if (userId.equalsIgnoreCase((String) s.get("workerId"))) {
                                    result.add(s);
                                }
                            }
                        } else {
                            // STAFF or ADMIN gets all
                            result.addAll(db.schedules);
                        }
                    }
                    sendResponse(exchange, 200, toJson(result));

                } else if ("POST".equalsIgnoreCase(method)) {
                    // Only STAFF can create/assign a schedule
                    if (!"STAFF".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403,
                                "{\"error\":\"Forbidden: Only office staff can assign schedules\"}");
                        return;
                    }

                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                    String area = (String) req.get("area");
                    String date = (String) req.get("date");
                    String time = (String) req.get("time");
                    String workerId = (String) req.get("workerId");

                    if (area == null || date == null || time == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing area, date, or time\"}");
                        return;
                    }

                    synchronized (db) {
                        // If no worker specified, auto-assign
                        if (workerId == null || workerId.trim().isEmpty()) {
                            workerId = getWorkerForArea(db, area);
                            if (workerId == null) {
                                sendResponse(exchange, 400,
                                        "{\"error\":\"No workers available to assign to this area. Register a worker first.\" }");
                                return;
                            }
                        }

                        Map<String, Object> sched = new LinkedHashMap<>();
                        sched.put("id", "S-" + UUID.randomUUID().toString().substring(0, 8));
                        sched.put("area", area);
                        sched.put("date", date);
                        sched.put("time", time);
                        sched.put("workerId", workerId);
                        sched.put("status", "Pending");
                        db.schedules.add(sched);
                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Schedule created successfully");
                    sendResponse(exchange, 200, toJson(resp));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/requests (Special pickup & dumping report & Guest reports)
    // Guest user can call POST without authentication!
    static class RequestsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            String method = exchange.getRequestMethod();

            try {
                if ("POST".equalsIgnoreCase(method)) {
                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                    String type = (String) req.get("type"); // USER_REQUEST, GUEST_REPORT, DUMPING_REPORT
                    String reporterName = (String) req.get("reporterName");
                    String phone = (String) req.get("phone");
                    String address = (String) req.get("address");
                    String area = (String) req.get("area");
                    String description = (String) req.get("description");

                    if (type == null || address == null || area == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing required fields: type, address, area\"}");
                        return;
                    }

                    // Check Auth for registered requests
                    Map<String, Object> session = null;
                    if (!"GUEST_REPORT".equalsIgnoreCase(type)) {
                        session = checkAuth(exchange, "USER");
                        if (session == null)
                            return; // checkAuth handles response
                    }

                    String userId = (session != null) ? (String) session.get("userId") : "GUEST";
                    if (reporterName == null && session != null) {
                        reporterName = (String) session.get("name");
                    }

                    synchronized (db) {
                        // Auto-assign worker based on the area
                        String workerId = getWorkerForArea(db, area);

                        String reqId = "R-" + UUID.randomUUID().toString().substring(0, 8);
                        Map<String, Object> newReq = new LinkedHashMap<>();
                        newReq.put("id", reqId);
                        newReq.put("type", type.toUpperCase());
                        newReq.put("userId", userId);
                        newReq.put("reporterName", reporterName != null ? reporterName : "Anonymous Guest");
                        newReq.put("phone", phone != null ? phone : "");
                        newReq.put("address", address);
                        newReq.put("area", area);
                        newReq.put("description", description != null ? description : "");
                        newReq.put("status", workerId != null ? "Assigned" : "Pending");
                        newReq.put("workerId", workerId != null ? workerId : "");
                        newReq.put("createdAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));

                        db.requests.add(newReq);

                        // If worker assigned, automatically add to the worker's schedule
                        if (workerId != null) {
                            Map<String, Object> sched = new LinkedHashMap<>();
                            sched.put("id", reqId); // map schedule id to request id
                            sched.put("area", area);
                            sched.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
                            sched.put("time", "Immediate");
                            sched.put("workerId", workerId);
                            sched.put("status", "Pending");
                            db.schedules.add(sched);
                        }

                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Waste collection request submitted and auto-assigned successfully.");
                    sendResponse(exchange, 200, toJson(resp));

                } else if ("GET".equalsIgnoreCase(method)) {
                    // Requires STAFF or ADMIN
                    Map<String, Object> session = checkAuth(exchange, "STAFF", "ADMIN");
                    if (session == null)
                        return;

                    List<Map<String, Object>> result;
                    synchronized (db) {
                        result = new ArrayList<>(db.requests);
                    }
                    sendResponse(exchange, 200, toJson(result));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/payments
    static class PaymentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange);
            if (session == null)
                return;

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    String role = (String) session.get("role");
                    String userId = (String) session.get("userId");
                    List<Map<String, Object>> result = new ArrayList<>();

                    synchronized (db) {
                        if ("USER".equalsIgnoreCase(role)) {
                            // Users see only their payments
                            for (Map<String, Object> p : db.payments) {
                                if (userId.equals(p.get("userId"))) {
                                    result.add(p);
                                }
                            }
                        } else if ("ADMIN".equalsIgnoreCase(role) || "STAFF".equalsIgnoreCase(role)) {
                            // Admin and Staff view all payments
                            result.addAll(db.payments);
                        } else {
                            sendResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                            return;
                        }
                    }
                    sendResponse(exchange, 200, toJson(result));

                } else if ("POST".equalsIgnoreCase(method)) {
                    // Only USER can pay
                    if (!"USER".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403, "{\"error\":\"Forbidden: Only users can make payments\"}");
                        return;
                    }

                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();
                    String userId = (String) session.get("userId");

                    Number amountNum = (Number) req.get("amount");
                    if (amountNum == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing payment amount\"}");
                        return;
                    }
                    double amount = amountNum.doubleValue();

                    synchronized (db) {
                        // Calculate rewards = 2% of payment amount
                        double rewardPointsEarned = amount * 0.02;

                        // Create payment record
                        Map<String, Object> pay = new LinkedHashMap<>();
                        pay.put("id", "P-" + UUID.randomUUID().toString().substring(0, 8));
                        pay.put("userId", userId);
                        pay.put("amount", amount);
                        pay.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                        pay.put("status", "Paid");
                        pay.put("rewardAdded", rewardPointsEarned);
                        db.payments.add(pay);

                        // Update user rewards
                        for (Map<String, Object> u : db.users) {
                            if (userId.equals(u.get("userId"))) {
                                double currentPoints = 0.0;
                                if (u.containsKey("rewardPoints")) {
                                    currentPoints = ((Number) u.get("rewardPoints")).doubleValue();
                                }
                                u.put("rewardPoints", currentPoints + rewardPointsEarned);
                                break;
                            }
                        }
                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Payment processed successfully. 2% reward points added!");
                    sendResponse(exchange, 200, toJson(resp));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/payments/redeem
    static class RedeemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange, "USER");
            if (session == null)
                return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();
                String userId = (String) session.get("userId");

                Number pointsNum = (Number) req.get("points");
                if (pointsNum == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing points to redeem\"}");
                    return;
                }
                double pointsToRedeem = pointsNum.doubleValue();

                synchronized (db) {
                    Map<String, Object> user = null;
                    for (Map<String, Object> u : db.users) {
                        if (userId.equals(u.get("userId"))) {
                            user = u;
                            break;
                        }
                    }

                    if (user == null) {
                        sendResponse(exchange, 404, "{\"error\":\"User not found\"}");
                        return;
                    }

                    double currentPoints = 0.0;
                    if (user.containsKey("rewardPoints")) {
                        currentPoints = ((Number) user.get("rewardPoints")).doubleValue();
                    }

                    if (currentPoints < pointsToRedeem) {
                        sendResponse(exchange, 400,
                                "{\"error\":\"Insufficient reward points. You have " + currentPoints + " points.\"}");
                        return;
                    }

                    // Deduct points
                    user.put("rewardPoints", currentPoints - pointsToRedeem);
                    db.save();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Redeemed " + pointsToRedeem + " reward points successfully!");
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET /api/workers (Gets all workers for Office Staff / Admin)
    static class WorkersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange, "STAFF", "ADMIN");
            if (session == null)
                return;

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                List<Map<String, Object>> workers = new ArrayList<>();
                synchronized (db) {
                    for (Map<String, Object> u : db.users) {
                        if ("WORKER".equalsIgnoreCase((String) u.get("role"))) {
                            Map<String, Object> w = new LinkedHashMap<>();
                            w.put("userId", u.get("userId"));
                            w.put("name", u.get("name"));
                            w.put("phone", u.get("phone"));
                            w.put("email", u.get("email"));
                            w.put("address", u.get("address"));
                            w.put("area", u.get("area"));
                            workers.add(w);
                        }
                    }
                }
                sendResponse(exchange, 200, toJson(workers));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/workers/assign (Office staff manually updates worker-area mapping)
    static class WorkersAssignHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange, "STAFF");
            if (session == null)
                return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                String workerId = (String) req.get("workerId");
                String area = (String) req.get("area");

                if (workerId == null || area == null || area.trim().isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing workerId or area\"}");
                    return;
                }

                synchronized (db) {
                    boolean found = false;
                    for (Map<String, Object> u : db.users) {
                        if (workerId.equalsIgnoreCase((String) u.get("userId"))
                                && "WORKER".equalsIgnoreCase((String) u.get("role"))) {
                            u.put("area", area);
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        sendResponse(exchange, 404, "{\"error\":\"Worker not found\"}");
                        return;
                    }

                    // Optional: reassign any future schedules in worker's previous area to other
                    // workers,
                    // and assign this worker to schedules in the new area.
                    db.save();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Worker reassigned to " + area + " successfully.");
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/workers/leave
    static class WorkersLeaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange);
            if (session == null)
                return;

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    // Only Office Staff can view/review leaves
                    if (!"STAFF".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                        return;
                    }
                    List<Map<String, Object>> result;
                    synchronized (db) {
                        result = new ArrayList<>(db.leaves);
                    }
                    sendResponse(exchange, 200, toJson(result));

                } else if ("POST".equalsIgnoreCase(method)) {
                    // Only Worker can apply
                    if (!"WORKER".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403,
                                "{\"error\":\"Forbidden: Only collection workers can apply for leave\"}");
                        return;
                    }

                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                    String startDate = (String) req.get("startDate");
                    String endDate = (String) req.get("endDate");
                    String reason = (String) req.get("reason");

                    if (startDate == null || endDate == null || reason == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing startDate, endDate, or reason\"}");
                        return;
                    }

                    synchronized (db) {
                        Map<String, Object> leave = new LinkedHashMap<>();
                        leave.put("id", "L-" + UUID.randomUUID().toString().substring(0, 8));
                        leave.put("workerId", session.get("userId"));
                        leave.put("workerName", session.get("name"));
                        leave.put("startDate", startDate);
                        leave.put("endDate", endDate);
                        leave.put("reason", reason);
                        leave.put("status", "Pending");

                        db.leaves.add(leave);
                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Leave application submitted successfully");
                    sendResponse(exchange, 200, toJson(resp));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/workers/expense
    static class WorkersExpenseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange);
            if (session == null)
                return;

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    // Office Staff / Admin can view expenses
                    if (!"STAFF".equalsIgnoreCase((String) session.get("role"))
                            && !"ADMIN".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                        return;
                    }
                    List<Map<String, Object>> result;
                    synchronized (db) {
                        result = new ArrayList<>(db.expenses);
                    }
                    sendResponse(exchange, 200, toJson(result));

                } else if ("POST".equalsIgnoreCase(method)) {
                    // Worker submits expense report
                    if (!"WORKER".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403,
                                "{\"error\":\"Forbidden: Only workers can submit expense reports\"}");
                        return;
                    }

                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                    Number amountNum = (Number) req.get("amount");
                    String description = (String) req.get("description");
                    String date = (String) req.get("date");

                    if (amountNum == null || description == null || date == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing amount, description, or date\"}");
                        return;
                    }

                    synchronized (db) {
                        Map<String, Object> exp = new LinkedHashMap<>();
                        exp.put("id", "EXP-" + UUID.randomUUID().toString().substring(0, 8));
                        exp.put("workerId", session.get("userId"));
                        exp.put("workerName", session.get("name"));
                        exp.put("amount", amountNum.doubleValue());
                        exp.put("description", description);
                        exp.put("date", date);
                        exp.put("status", "Pending");

                        db.expenses.add(exp);
                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Expense report submitted successfully.");
                    sendResponse(exchange, 200, toJson(resp));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // POST /api/workers/expense/action (Office staff accepts or declines expenses)
    static class WorkersExpenseActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange, "STAFF");
            if (session == null)
                return;

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            try {
                String body = readRequestBody(exchange);
                Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                String expId = (String) req.get("id");
                String action = (String) req.get("action"); // Accept, Decline

                if (expId == null || action == null) {
                    sendResponse(exchange, 400, "{\"error\":\"Missing expense id or action\"}");
                    return;
                }

                synchronized (db) {
                    boolean found = false;
                    for (Map<String, Object> exp : db.expenses) {
                        if (expId.equalsIgnoreCase((String) exp.get("id"))) {
                            if (action.equalsIgnoreCase("Accept")) {
                                exp.put("status", "Approved");
                            } else if (action.equalsIgnoreCase("Decline")) {
                                exp.put("status", "Declined");
                            } else {
                                sendResponse(exchange, 400,
                                        "{\"error\":\"Invalid action. Must be 'Accept' or 'Decline'\"}");
                                return;
                            }
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        sendResponse(exchange, 404, "{\"error\":\"Expense report not found\"}");
                        return;
                    }
                    db.save();
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Expense status updated to " + action + "ed successfully.");
                sendResponse(exchange, 200, toJson(resp));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // GET & POST /api/reports (Worker reports collection, Admin reviews reports)
    static class ReportsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange))
                return;
            Map<String, Object> session = checkAuth(exchange);
            if (session == null)
                return;

            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    // Admin & Staff can view reports
                    if (!"ADMIN".equalsIgnoreCase((String) session.get("role"))
                            && !"STAFF".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                        return;
                    }
                    List<Map<String, Object>> result;
                    synchronized (db) {
                        result = new ArrayList<>(db.reports);
                    }
                    sendResponse(exchange, 200, toJson(result));

                } else if ("POST".equalsIgnoreCase(method)) {
                    // Worker reports waste collection
                    if (!"WORKER".equalsIgnoreCase((String) session.get("role"))) {
                        sendResponse(exchange, 403,
                                "{\"error\":\"Forbidden: Only workers can submit collection reports\"}");
                        return;
                    }

                    String body = readRequestBody(exchange);
                    Map<String, Object> req = (Map<String, Object>) new JsonParser(body).parse();

                    String scheduleId = (String) req.get("scheduleId"); // matches schedule ID or request ID
                    String wasteType = (String) req.get("wasteType");
                    Number weightNum = (Number) req.get("weight");
                    String notes = (String) req.get("notes");

                    if (scheduleId == null || wasteType == null || weightNum == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Missing scheduleId, wasteType, or weight\"}");
                        return;
                    }

                    synchronized (db) {
                        // Mark schedule status as Collected
                        boolean foundSched = false;
                        String area = "Unknown Zone";
                        for (Map<String, Object> s : db.schedules) {
                            if (scheduleId.equalsIgnoreCase((String) s.get("id"))) {
                                s.put("status", "Collected");
                                s.put("updatedAt",
                                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                                area = (String) s.get("area");
                                foundSched = true;
                                break;
                            }
                        }

                        // Also check requests list and update status
                        for (Map<String, Object> r : db.requests) {
                            if (scheduleId.equalsIgnoreCase((String) r.get("id"))) {
                                r.put("status", "Collected");
                                area = (String) r.get("area");
                                break;
                            }
                        }

                        // Log the report
                        Map<String, Object> rep = new LinkedHashMap<>();
                        rep.put("id", "REP-" + UUID.randomUUID().toString().substring(0, 8));
                        rep.put("workerId", session.get("userId"));
                        rep.put("workerName", session.get("name"));
                        rep.put("scheduleId", scheduleId);
                        rep.put("area", area);
                        rep.put("wasteType", wasteType);
                        rep.put("weight", weightNum.doubleValue());
                        rep.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
                        rep.put("notes", notes != null ? notes : "");

                        db.reports.add(rep);
                        db.save();
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("success", true);
                    resp.put("message", "Waste collection completed and status updated.");
                    sendResponse(exchange, 200, toJson(resp));
                } else {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Server Error: " + e.getMessage() + "\"}");
            }
        }
    }

    // Helper to auto-assign workers based on area load balancing
    static String autoAssignWorkerArea(Database db) {
        String[] areas = { "North Zone", "South Zone", "East Zone", "West Zone", "Central Zone" };
        Map<String, Integer> counts = new HashMap<>();
        for (String area : areas) {
            counts.put(area, 0);
        }
        for (Map<String, Object> u : db.users) {
            if ("WORKER".equalsIgnoreCase((String) u.get("role"))) {
                String wArea = (String) u.get("area");
                if (wArea != null && counts.containsKey(wArea)) {
                    counts.put(wArea, counts.get(wArea) + 1);
                }
            }
        }
        String minArea = areas[0];
        int minVal = Integer.MAX_VALUE;
        for (String area : areas) {
            int val = counts.get(area);
            if (val < minVal) {
                minVal = val;
                minArea = area;
            }
        }
        return minArea;
    }

    // Find worker for an area
    static String getWorkerForArea(Database db, String area) {
        List<String> workersInArea = new ArrayList<>();
        for (Map<String, Object> u : db.users) {
            if ("WORKER".equalsIgnoreCase((String) u.get("role")) && area.equalsIgnoreCase((String) u.get("area"))) {
                workersInArea.add((String) u.get("userId"));
            }
        }
        if (!workersInArea.isEmpty()) {
            return workersInArea.get(0); // Pick first worker assigned to this area
        }
        // Fallback: pick any worker if none in this area
        for (Map<String, Object> u : db.users) {
            if ("WORKER".equalsIgnoreCase((String) u.get("role"))) {
                return (String) u.get("userId");
            }
        }
        return null;
    }

    // Detect area from address text
    private static String detectArea(String address) {
        if (address == null)
            return "Central Zone";
        String lower = address.toLowerCase();
        if (lower.contains("north"))
            return "North Zone";
        if (lower.contains("south"))
            return "South Zone";
        if (lower.contains("east"))
            return "East Zone";
        if (lower.contains("west"))
            return "West Zone";
        return "Central Zone";
    }

    // --- Database Schema and Load/Save Engine ---
    static class Database {
        public List<Map<String, Object>> users = new ArrayList<>();
        public List<Map<String, Object>> schedules = new ArrayList<>();
        public List<Map<String, Object>> requests = new ArrayList<>();
        public List<Map<String, Object>> payments = new ArrayList<>();
        public List<Map<String, Object>> leaves = new ArrayList<>();
        public List<Map<String, Object>> expenses = new ArrayList<>();
        public List<Map<String, Object>> reports = new ArrayList<>();

        public static Database load() {
            File file = new File("ecobin_db.json");
            if (file.exists()) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonParser parser = new JsonParser(content);
                    Map<String, Object> data = (Map<String, Object>) parser.parse();
                    Database db = new Database();
                    if (data.containsKey("users"))
                        db.users = (List<Map<String, Object>>) data.get("users");
                    if (data.containsKey("schedules"))
                        db.schedules = (List<Map<String, Object>>) data.get("schedules");
                    if (data.containsKey("requests"))
                        db.requests = (List<Map<String, Object>>) data.get("requests");
                    if (data.containsKey("payments"))
                        db.payments = (List<Map<String, Object>>) data.get("payments");
                    if (data.containsKey("leaves"))
                        db.leaves = (List<Map<String, Object>>) data.get("leaves");
                    if (data.containsKey("expenses"))
                        db.expenses = (List<Map<String, Object>>) data.get("expenses");
                    if (data.containsKey("reports"))
                        db.reports = (List<Map<String, Object>>) data.get("reports");
                    System.out.println("Database loaded successfully from ecobin_db.json");
                    return db;
                } catch (Exception e) {
                    System.err.println(
                            "Error reading ecobin_db.json, recreating with default values. Reason: " + e.getMessage());
                }
            }
            Database db = new Database();
            db.seedDefaults();
            db.save();
            return db;
        }

        public synchronized void save() {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("users", users);
                data.put("schedules", schedules);
                data.put("requests", requests);
                data.put("payments", payments);
                data.put("leaves", leaves);
                data.put("expenses", expenses);
                data.put("reports", reports);
                String json = toJson(data);
                Files.write(Paths.get("ecobin_db.json"), json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("Failed to write to database: " + e.getMessage());
            }
        }

        private void seedDefaults() {
            System.out.println("Seeding database with default records...");

            // 1. Admin
            Map<String, Object> admin = new LinkedHashMap<>();
            admin.put("userId", "admin");
            admin.put("password", "admin123");
            admin.put("name", "System Admin");
            admin.put("role", "ADMIN");
            users.add(admin);

            // 2. Default Office Staff
            Map<String, Object> staff = new LinkedHashMap<>();
            staff.put("userId", "staff1");
            staff.put("password", "staff123");
            staff.put("name", "Sarah Connor");
            staff.put("role", "STAFF");
            staff.put("phone", "555-0101");
            staff.put("email", "sarah@ecobin.org");
            staff.put("address", "101 Administration building");
            staff.put("area", "Headquarters");
            users.add(staff);

            // 3. Default Collection Workers
            Map<String, Object> worker1 = new LinkedHashMap<>();
            worker1.put("userId", "worker1");
            worker1.put("password", "worker123");
            worker1.put("name", "John Doe");
            worker1.put("role", "WORKER");
            worker1.put("phone", "555-0202");
            worker1.put("email", "john.doe@ecobin.org");
            worker1.put("address", "202 Depot Road");
            worker1.put("area", "North Zone");
            users.add(worker1);

            Map<String, Object> worker2 = new LinkedHashMap<>();
            worker2.put("userId", "worker2");
            worker2.put("password", "worker123");
            worker2.put("name", "Bob Smith");
            worker2.put("role", "WORKER");
            worker2.put("phone", "555-0303");
            worker2.put("email", "bob.smith@ecobin.org");
            worker2.put("address", "303 Depot Road");
            worker2.put("area", "South Zone");
            users.add(worker2);

            // 4. Default Registered User
            Map<String, Object> rUser = new LinkedHashMap<>();
            rUser.put("userId", "user1");
            rUser.put("password", "user123");
            rUser.put("name", "Alice Green");
            rUser.put("role", "USER");
            rUser.put("phone", "555-0404");
            rUser.put("email", "alice.green@gmail.com");
            rUser.put("address", "789 Pine Road, North Zone");
            rUser.put("area", "North Zone");
            rUser.put("rewardPoints", 15.0);
            users.add(rUser);

            // 5. Seed schedules
            String monthPrefix = "2026-06-";
            String[] times = { "09:00", "11:00", "14:00" };
            String[] workers = { "worker1", "worker2" };
            String[] areas = { "North Zone", "South Zone" };

            for (int i = 1; i <= 3; i++) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", "S-00" + i);
                s.put("area", areas[i % 2]);
                s.put("date", monthPrefix + String.format("%02d", i * 5));
                s.put("time", times[i % 3]);
                s.put("workerId", workers[i % 2]);
                s.put("status", "Pending");
                schedules.add(s);
            }
        }
    }
}
