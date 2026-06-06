# Flash Sale System — Full Implementation Plan

## Problem Statement
Build a backend that handles thousands of concurrent users trying to buy
limited-stock items during a time-bounded flash sale. Requirements:
- Zero overselling under any concurrency level
- Fair queue-based order processing
- Waitlist for sold-out items
- Real-time sale status
- Survives traffic spikes without degrading

---

## Your Reusable Libraries Used
| Library | Used In | Purpose |
|---|---|---|
| reusable-rate-limiter | api-gateway | Throttle burst traffic before it hits services |
| ReusableSecurityModule | api-gateway | JWT auth for all routes |
| reusable-sms-email-service-module | notification-service | Purchase confirmation + waitlist alerts |
| Audit-in-springboot | sale-service, order-service | createdAt, updatedAt, createdBy on all entities |

---

## Services Overview

```
api-gateway          (8080)  → auth + rate limiting
sale-service         (8081)  → manages sales, stock counters in Redis
order-service        (8082)  → drains Kafka queue, persists orders
waitlist-service     (8083)  → overflow queue, notifies on restock
notification-service (8084)  → email/SMS via communication-module
```

---

## The Core Problem: Why This Is Hard

Naive approach (WRONG):
```
1. Read stock from DB       → stock = 5
2. Check if stock > 0       → yes
3. Decrement stock in DB    → stock = 4
4. Create order             → ok
```
With 1000 concurrent users all reading stock=5 at step 1,
all pass the check, all decrement — you end up with -995 stock.
This is a race condition. Redis atomic operations solve it.

Correct approach:
```
Redis DECR stock:sale123 → atomic, returns new value
if new value >= 0  → allowed, push to Kafka
if new value < 0   → oversold, INCR back immediately, reject
```
Redis single-threaded command execution guarantees atomicity.
No two DECR calls interleave.

---

## System Flow

### Happy Path (stock available)
```
User → POST /api/sales/{saleId}/buy
         │
         ▼
    api-gateway
    ├── JWT validation (security-module)
    └── Rate limit check (rate-limiter) → 429 if exceeded
         │
         ▼
    sale-service
    ├── Is sale window open?           → 400 if not
    ├── Redis DECR stock:saleId        → atomic decrement
    ├── New value >= 0?                → proceed
    └── Push OrderRequest to Kafka [sale.order.requested]
         │
         ▼
    order-service (Kafka consumer)
    ├── Dequeue from [sale.order.requested]
    ├── Idempotency check (orderId already exists?)
    ├── Persist order (CONFIRMED)
    └── Publish [order.confirmed]
         │
         ▼
    notification-service
    └── Send confirmation email/SMS
```

### Sold Out Path
```
User → POST /api/sales/{saleId}/buy
         │
         ▼
    sale-service
    ├── Redis DECR stock:saleId → returns -1
    ├── Redis INCR stock:saleId → restore (compensate)
    └── Return 409 SOLD_OUT
         │
         ▼ (if user wants to be notified)
    POST /api/sales/{saleId}/waitlist
         │
         ▼
    waitlist-service
    └── Add userId to Redis ZADD waitlist:saleId score=timestamp
```

### Restock / Cancellation Path
```
Admin → PATCH /api/sales/{saleId}/restock (quantity=10)
         │
         ▼
    sale-service
    ├── Redis INCRBY stock:saleId 10
    └── Publish [sale.restocked] with quantity=10
         │
         ▼
    waitlist-service
    ├── Pop top 10 users from waitlist (ZPOPMIN)
    └── Publish [waitlist.notify] for each user
         │
         ▼
    notification-service
    └── "Good news! Stock is available. Buy now."
```

---

## docker-compose.yml

```yaml
version: '3.8'

services:

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --save "" --appendonly no

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: flashsale_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"

  maildev:
    image: maildev/maildev
    ports:
      - "1080:1080"
      - "1025:1025"

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
```

---

## Kafka Topics

| Topic | Producer | Consumer | Purpose |
|---|---|---|---|
| sale.order.requested | sale-service | order-service | Order drain queue |
| order.confirmed | order-service | notification-service | Send confirmation |
| order.failed | order-service | sale-service | Restore stock on failure |
| sale.restocked | sale-service | waitlist-service | Trigger waitlist notification |
| waitlist.notify | waitlist-service | notification-service | Send availability alert |

---

## Database Schema (single PostgreSQL, all services share)

```sql
-- sale-service tables
CREATE TABLE sales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    product_id VARCHAR(100) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    total_stock INT NOT NULL,
    max_per_user INT NOT NULL DEFAULT 1,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(100),
    last_modified_by VARCHAR(100)
);

-- order-service tables
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id UUID NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    product_id VARCHAR(100) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(30) NOT NULL,           -- CONFIRMED, FAILED, CANCELLED
    idempotency_key VARCHAR(255) UNIQUE,   -- prevents double orders
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(100)
);

-- waitlist-service tables
CREATE TABLE waitlist_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id UUID NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT now(),
    notified BOOLEAN DEFAULT FALSE,
    UNIQUE(sale_id, user_id)               -- one entry per user per sale
);
```

---

## Redis Key Design

```
stock:{saleId}              → Integer   current available stock (DECR/INCR)
sale:meta:{saleId}          → Hash      title, startAt, endAt, maxPerUser (cached)
sale:status:{saleId}        → String    SCHEDULED|ACTIVE|SOLD_OUT|ENDED
purchased:{saleId}:{userId} → Integer   how many this user has bought (enforce maxPerUser)
waitlist:{saleId}           → Sorted Set  userId → timestamp score (ZADD/ZPOPMIN)
lock:buy:{saleId}:{userId}  → String    distributed lock (prevent double-click)
```

---

# SERVICE 1: api-gateway (port 8080)

## pom.xml
```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
<!-- Your libraries -->
<dependency>
  <groupId>com.security</groupId>
  <artifactId>security-module</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>com.ratelimiter</groupId>
  <artifactId>rate-limiter-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

## application.yml
```yaml
spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: sale-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/sales/**
        - id: order-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/orders/**
        - id: waitlist-service
          uri: http://localhost:8083
          predicates:
            - Path=/api/waitlist/**
  data:
    redis:
      host: localhost
      port: 6379

rate-limiter:
  enabled: true
  namespace: flash-sale
  default-limit: 50              # 50 req/min per client normally
  default-window-seconds: 60
  http-filter:
    enabled: true
  clients:
    # tighter limit on the buy endpoint specifically
    buy-endpoint:
      limit: 5
      window-seconds: 10

server:
  port: 8080
```

---

# SERVICE 2: sale-service (port 8081)

## Purpose
Manages sale lifecycle and owns the Redis stock counter.
This is the hottest service — all buy requests hit it first.

## application.yml
```yaml
spring:
  application:
    name: sale-service
  datasource:
    url: jdbc:postgresql://localhost:5432/flashsale_db
    username: postgres
    password: postgres
  data:
    redis:
      host: localhost
      port: 6379
  jpa:
    hibernate:
      ddl-auto: update
server:
  port: 8081
```

## pom.xml additions
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
  <groupId>com.audit</groupId>
  <artifactId>audit-module</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Sale.java (Entity)
```java
@Entity
@Table(name = "sales")
@EntityListeners(AuditingEntityListener.class)
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String title;
    private String productId;
    private String productName;
    private BigDecimal price;
    private Integer totalStock;
    private Integer maxPerUser;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    private SaleStatus status = SaleStatus.SCHEDULED;

    @CreatedDate  private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;
    @CreatedBy    private String createdBy;
    @LastModifiedBy private String lastModifiedBy;
}
```

## SaleStatus.java
```java
public enum SaleStatus {
    SCHEDULED, ACTIVE, SOLD_OUT, ENDED
}
```

## StockService.java (the critical class)
```java
@Service
public class StockService {

    private final StringRedisTemplate redis;

    private static final String STOCK_KEY      = "stock:%s";
    private static final String PURCHASED_KEY  = "purchased:%s:%s";
    private static final String LOCK_KEY       = "lock:buy:%s:%s";

    // Called when a sale goes ACTIVE — loads stock into Redis
    public void initializeStock(UUID saleId, int stock) {
        redis.opsForValue().set(stockKey(saleId), String.valueOf(stock));
    }

    // Core buy method — returns true if stock successfully reserved
    public StockReservationResult tryReserve(UUID saleId, String userId, int maxPerUser) {

        // Step 1: Per-user limit check (atomic)
        String purchasedKey = String.format(PURCHASED_KEY, saleId, userId);
        Long userCount = redis.opsForValue().increment(purchasedKey);
        redis.expire(purchasedKey, Duration.ofDays(1));

        if (userCount > maxPerUser) {
            redis.opsForValue().decrement(purchasedKey); // undo
            return StockReservationResult.limitExceeded();
        }

        // Step 2: Global stock decrement (atomic)
        Long remaining = redis.opsForValue().decrement(stockKey(saleId));

        if (remaining >= 0) {
            return StockReservationResult.success(remaining);
        }

        // Step 3: Oversold — restore both counters
        redis.opsForValue().increment(stockKey(saleId));
        redis.opsForValue().decrement(purchasedKey);
        return StockReservationResult.soldOut();
    }

    // Called when order processing fails — give stock back
    public void releaseStock(UUID saleId, String userId) {
        redis.opsForValue().increment(stockKey(saleId));
        String purchasedKey = String.format(PURCHASED_KEY, saleId, userId);
        redis.opsForValue().decrement(purchasedKey);
    }

    public Long getCurrentStock(UUID saleId) {
        String val = redis.opsForValue().get(stockKey(saleId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    private String stockKey(UUID saleId) {
        return String.format(STOCK_KEY, saleId);
    }
}
```

## StockReservationResult.java
```java
public record StockReservationResult(
    boolean success,
    RejectReason reason,
    long remaining
) {
    public enum RejectReason { SOLD_OUT, LIMIT_EXCEEDED, SALE_NOT_ACTIVE }

    public static StockReservationResult success(long remaining) {
        return new StockReservationResult(true, null, remaining);
    }
    public static StockReservationResult soldOut() {
        return new StockReservationResult(false, RejectReason.SOLD_OUT, 0);
    }
    public static StockReservationResult limitExceeded() {
        return new StockReservationResult(false, RejectReason.LIMIT_EXCEEDED, 0);
    }
}
```

## SaleService.java
```java
@Service
public class SaleService {

    private final SaleRepository saleRepo;
    private final StockService stockService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redis;

    public BuyResponse buy(UUID saleId, String userId, BuyRequest request) {
        // 1. Validate sale is active
        Sale sale = saleRepo.findById(saleId)
            .orElseThrow(() -> new SaleNotFoundException(saleId));

        if (sale.getStatus() != SaleStatus.ACTIVE) {
            throw new SaleNotActiveException(sale.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(sale.getStartsAt()) || now.isAfter(sale.getEndsAt())) {
            throw new SaleWindowClosedException();
        }

        // 2. Try to reserve stock atomically
        StockReservationResult result = stockService.tryReserve(
            saleId, userId, sale.getMaxPerUser()
        );

        if (!result.success()) {
            if (result.reason() == RejectReason.SOLD_OUT) {
                // Update sale status to SOLD_OUT if stock hits 0
                if (stockService.getCurrentStock(saleId) <= 0) {
                    sale.setStatus(SaleStatus.SOLD_OUT);
                    saleRepo.save(sale);
                }
                throw new SoldOutException(saleId);
            }
            if (result.reason() == RejectReason.LIMIT_EXCEEDED) {
                throw new PurchaseLimitExceededException(sale.getMaxPerUser());
            }
        }

        // 3. Push to Kafka — order-service will persist async
        String idempotencyKey = saleId + ":" + userId + ":" + System.currentTimeMillis();
        OrderRequestEvent event = new OrderRequestEvent(
            UUID.randomUUID(),
            saleId,
            userId,
            sale.getProductId(),
            sale.getPrice(),
            idempotencyKey
        );
        kafkaTemplate.send("sale.order.requested", saleId.toString(), event);

        return new BuyResponse(event.orderId(), "Order queued successfully", result.remaining());
    }

    // Called by scheduler — activates sales when window opens
    @Scheduled(fixedDelay = 10000)
    public void activatePendingSales() {
        LocalDateTime now = LocalDateTime.now();
        List<Sale> toActivate = saleRepo.findByStatusAndStartsAtBefore(SaleStatus.SCHEDULED, now);
        for (Sale sale : toActivate) {
            sale.setStatus(SaleStatus.ACTIVE);
            saleRepo.save(sale);
            stockService.initializeStock(sale.getId(), sale.getTotalStock());
            log.info("Sale {} activated with stock {}", sale.getId(), sale.getTotalStock());
        }
    }

    // Called by scheduler — ends sales when window closes
    @Scheduled(fixedDelay = 10000)
    public void endExpiredSales() {
        LocalDateTime now = LocalDateTime.now();
        List<Sale> toEnd = saleRepo.findByStatusInAndEndsAtBefore(
            List.of(SaleStatus.ACTIVE, SaleStatus.SOLD_OUT), now
        );
        toEnd.forEach(sale -> {
            sale.setStatus(SaleStatus.ENDED);
            saleRepo.save(sale);
        });
    }

    public void restock(UUID saleId, int quantity) {
        Sale sale = saleRepo.findById(saleId).orElseThrow();
        sale.setTotalStock(sale.getTotalStock() + quantity);
        if (sale.getStatus() == SaleStatus.SOLD_OUT) {
            sale.setStatus(SaleStatus.ACTIVE);
        }
        saleRepo.save(sale);
        redis.opsForValue().increment("stock:" + saleId, quantity);
        kafkaTemplate.send("sale.restocked", new SaleRestockedEvent(saleId, quantity));
    }
}
```

## SaleController.java
```java
@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final SaleService saleService;
    private final StockService stockService;

    // Admin: create a sale
    @PostMapping
    public ResponseEntity<Sale> createSale(@RequestBody CreateSaleRequest request) {
        return ResponseEntity.status(201).body(saleService.createSale(request));
    }

    // User: attempt to buy
    @PostMapping("/{saleId}/buy")
    public ResponseEntity<BuyResponse> buy(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId,
            @RequestBody BuyRequest request) {
        return ResponseEntity.ok(saleService.buy(saleId, userId, request));
    }

    // Public: check sale status + remaining stock
    @GetMapping("/{saleId}/status")
    public ResponseEntity<SaleStatusResponse> getStatus(@PathVariable UUID saleId) {
        Sale sale = saleService.getSale(saleId);
        Long stock = stockService.getCurrentStock(saleId);
        return ResponseEntity.ok(new SaleStatusResponse(sale.getStatus(), stock, sale.getEndsAt()));
    }

    // Admin: restock
    @PatchMapping("/{saleId}/restock")
    public ResponseEntity<Void> restock(
            @PathVariable UUID saleId,
            @RequestParam int quantity) {
        saleService.restock(saleId, quantity);
        return ResponseEntity.ok().build();
    }
}
```

## SaleEventConsumer.java
```java
@Component
public class SaleEventConsumer {

    private final StockService stockService;

    // If order-service fails to persist — give stock back
    @KafkaListener(topics = "order.failed", groupId = "sale-service")
    public void onOrderFailed(OrderFailedEvent event) {
        stockService.releaseStock(event.saleId(), event.userId());
        log.warn("Order {} failed — stock restored for sale {}", event.orderId(), event.saleId());
    }
}
```

---

# SERVICE 3: order-service (port 8082)

## Purpose
Drains the Kafka queue and persists orders. Decoupled from the buy flow —
sale-service responds immediately, order-service works at its own pace.

## application.yml
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/flashsale_db
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update
server:
  port: 8082
```

## Order.java (Entity)
```java
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    private UUID id;

    private UUID saleId;
    private String userId;
    private String productId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(unique = true)
    private String idempotencyKey;   // prevents double-processing

    @CreatedDate  private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;
    @CreatedBy    private String createdBy;
}
```

## OrderService.java
```java
@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void processOrderRequest(OrderRequestEvent event) {

        // Idempotency: if already processed, skip silently
        if (orderRepo.existsByIdempotencyKey(event.idempotencyKey())) {
            log.warn("Duplicate order request ignored: {}", event.idempotencyKey());
            return;
        }

        try {
            Order order = new Order();
            order.setId(event.orderId());
            order.setSaleId(event.saleId());
            order.setUserId(event.userId());
            order.setProductId(event.productId());
            order.setAmount(event.amount());
            order.setStatus(OrderStatus.CONFIRMED);
            order.setIdempotencyKey(event.idempotencyKey());
            orderRepo.save(order);

            kafkaTemplate.send("order.confirmed",
                new OrderConfirmedEvent(event.orderId(), event.saleId(), event.userId(), event.amount()));

        } catch (Exception e) {
            log.error("Failed to persist order {}: {}", event.orderId(), e.getMessage());
            kafkaTemplate.send("order.failed",
                new OrderFailedEvent(event.orderId(), event.saleId(), event.userId(), e.getMessage()));
        }
    }
}
```

## OrderEventConsumer.java
```java
@Component
public class OrderEventConsumer {

    private final OrderService orderService;

    @KafkaListener(
        topics = "sale.order.requested",
        groupId = "order-service",
        concurrency = "3"       // 3 concurrent consumer threads — controls drain speed
    )
    public void onOrderRequested(OrderRequestEvent event) {
        orderService.processOrderRequest(event);
    }
}
```

## OrderController.java
```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepo;

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable UUID orderId) {
        return orderRepo.findById(orderId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getUserOrders(@PathVariable String userId) {
        return ResponseEntity.ok(orderRepo.findByUserId(userId));
    }
}
```

---

# SERVICE 4: waitlist-service (port 8083)

## Purpose
Stores overflow users in a Redis sorted set (fair FIFO by join time).
When stock is restocked, pops top N users and triggers notifications.

## application.yml
```yaml
spring:
  application:
    name: waitlist-service
  datasource:
    url: jdbc:postgresql://localhost:5432/flashsale_db
    username: postgres
    password: postgres
  data:
    redis:
      host: localhost
      port: 6379
server:
  port: 8083
```

## WaitlistService.java
```java
@Service
public class WaitlistService {

    private final StringRedisTemplate redis;
    private final WaitlistEntryRepository waitlistRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String WAITLIST_KEY = "waitlist:%s";

    public WaitlistJoinResponse join(UUID saleId, String userId, String email) {
        String key = String.format(WAITLIST_KEY, saleId);

        // Check if already in waitlist
        Double score = redis.opsForZSet().score(key, userId);
        if (score != null) {
            long position = redis.opsForZSet().rank(key, userId) + 1;
            return new WaitlistJoinResponse(false, "Already on waitlist", position);
        }

        // Add to Redis sorted set — score = epoch ms = fair FIFO order
        double joinTime = System.currentTimeMillis();
        redis.opsForZSet().add(key, userId, joinTime);

        // Persist to DB for durability
        WaitlistEntry entry = new WaitlistEntry(saleId, userId, email, LocalDateTime.now());
        waitlistRepo.save(entry);

        long position = redis.opsForZSet().rank(key, userId) + 1;
        long totalWaiting = redis.opsForZSet().size(key);

        return new WaitlistJoinResponse(true, "Added to waitlist", position);
    }

    public WaitlistPositionResponse getPosition(UUID saleId, String userId) {
        String key = String.format(WAITLIST_KEY, saleId);
        Long rank = redis.opsForZSet().rank(key, userId);
        if (rank == null) return new WaitlistPositionResponse(false, 0, 0);

        long total = redis.opsForZSet().size(key);
        return new WaitlistPositionResponse(true, rank + 1, total);
    }

    // Called when sale is restocked
    public void notifyTopWaiters(UUID saleId, int quantity) {
        String key = String.format(WAITLIST_KEY, saleId);

        // Pop top N users (lowest score = earliest join time)
        Set<String> topUsers = redis.opsForZSet().popMin(key, quantity)
            .stream()
            .map(TypedTuple::getValue)
            .collect(Collectors.toSet());

        for (String userId : topUsers) {
            WaitlistEntry entry = waitlistRepo.findBySaleIdAndUserId(saleId, userId)
                .orElse(null);
            if (entry != null) {
                entry.setNotified(true);
                waitlistRepo.save(entry);
                kafkaTemplate.send("waitlist.notify",
                    new WaitlistNotifyEvent(saleId, userId, entry.getEmail()));
            }
        }
    }
}
```

## WaitlistEventConsumer.java
```java
@Component
public class WaitlistEventConsumer {

    private final WaitlistService waitlistService;

    @KafkaListener(topics = "sale.restocked", groupId = "waitlist-service")
    public void onSaleRestocked(SaleRestockedEvent event) {
        waitlistService.notifyTopWaiters(event.saleId(), event.quantity());
    }
}
```

## WaitlistController.java
```java
@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    @PostMapping("/{saleId}/join")
    public ResponseEntity<WaitlistJoinResponse> join(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId,
            @RequestBody WaitlistJoinRequest request) {
        return ResponseEntity.ok(waitlistService.join(saleId, userId, request.getEmail()));
    }

    @GetMapping("/{saleId}/position")
    public ResponseEntity<WaitlistPositionResponse> getPosition(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId) {
        return ResponseEntity.ok(waitlistService.getPosition(saleId, userId));
    }
}
```

---

# SERVICE 5: notification-service (port 8084)

## application.yml
```yaml
spring:
  application:
    name: notification-service
  mail:
    host: localhost
    port: 1025         # Maildev — free local SMTP
    username: ""
    password: ""
    properties:
      mail.smtp.auth: false
      mail.smtp.starttls.enable: false

communication:
  email:
    provider: SMTP
    from: noreply@flashsale.local

server:
  port: 8084
```

## NotificationEventConsumer.java
```java
@Component
public class NotificationEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "order.confirmed", groupId = "notification-service")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        notificationService.sendPurchaseConfirmation(event);
    }

    @KafkaListener(topics = "waitlist.notify", groupId = "notification-service")
    public void onWaitlistNotify(WaitlistNotifyEvent event) {
        notificationService.sendWaitlistAlert(event);
    }
}
```

## NotificationService.java
```java
@Service
public class NotificationService {

    private final EmailSender emailSender;  // your communication-module bean

    public void sendPurchaseConfirmation(OrderConfirmedEvent event) {
        emailSender.send(EmailRequest.builder()
            .to(event.userId() + "@example.com")
            .subject("Purchase Confirmed — Order #" + event.orderId())
            .body("Your purchase is confirmed! Order: " + event.orderId()
                + " | Amount: ₹" + event.amount())
            .build());
    }

    public void sendWaitlistAlert(WaitlistNotifyEvent event) {
        emailSender.send(EmailRequest.builder()
            .to(event.email())
            .subject("Stock Available — Hurry!")
            .body("Good news! The item you were waiting for is back in stock. "
                + "Visit now before it sells out again.")
            .build());
    }
}
```

---

## All Kafka Event DTOs

```java
// Produced by sale-service
public record OrderRequestEvent(
    UUID orderId, UUID saleId, String userId,
    String productId, BigDecimal amount, String idempotencyKey) {}

public record SaleRestockedEvent(UUID saleId, int quantity) {}

// Produced by order-service
public record OrderConfirmedEvent(UUID orderId, UUID saleId, String userId, BigDecimal amount) {}
public record OrderFailedEvent(UUID orderId, UUID saleId, String userId, String reason) {}

// Produced by waitlist-service
public record WaitlistNotifyEvent(UUID saleId, String userId, String email) {}
```

---

## Exception Handling (GlobalExceptionHandler.java — add to sale-service)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(SoldOutException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SOLD_OUT", "Item is sold out", e.getSaleId()));
    }

    @ExceptionHandler(PurchaseLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLimitExceeded(PurchaseLimitExceededException e) {
        return ResponseEntity.status(429)
            .body(new ErrorResponse("LIMIT_EXCEEDED",
                "You can only buy " + e.getLimit() + " per sale", null));
    }

    @ExceptionHandler(SaleNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleNotActive(SaleNotActiveException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("SALE_NOT_ACTIVE",
                "Sale is currently " + e.getStatus(), null));
    }
}
```

---

## Load Testing with k6 (free, no paid subscription)

Install: https://k6.io/docs/getting-started/installation/

### load-test.js
```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    flash_sale_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 100 },   // ramp to 100 users
        { duration: '30s', target: 500 },   // spike to 500 users
        { duration: '10s', target: 0 },     // ramp down
      ],
    },
  },
};

const SALE_ID = __ENV.SALE_ID || 'your-sale-uuid-here';

export default function () {
  const res = http.post(
    `http://localhost:8080/api/sales/${SALE_ID}/buy`,
    JSON.stringify({ quantity: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + __ENV.TOKEN,
        'X-User-ID': `user-${__VU}`,         // each virtual user has unique ID
        'X-Client-ID': `user-${__VU}`,
      },
    }
  );

  check(res, {
    'status is 200 (got item)':  (r) => r.status === 200,
    'status is 409 (sold out)':  (r) => r.status === 409,
    'status is 429 (rate limit)': (r) => r.status === 429,
    'no 500 errors':             (r) => r.status !== 500,
  });

  sleep(0.1);
}
```

Run: `k6 run -e SALE_ID=xxx -e TOKEN=yyy load-test.js`

**What to verify:** Total orders confirmed in DB should never exceed total_stock. This is the proof of zero overselling.

---

## Implementation Order

### Week 1 — Foundation
1. Run `docker compose up -d`
2. mvn install all your reusable libraries
3. Create all 5 Spring Boot projects (use Spring Initializr)
4. Set up database schema
5. Implement Sale entity + SaleRepository + basic CRUD in sale-service

### Week 2 — Core Flow
6. Implement StockService (Redis DECR/INCR logic) — write unit tests first
7. Implement SaleService.buy() + Kafka publish
8. Implement OrderEventConsumer in order-service
9. Test: POST /buy → check Kafka message received → check order in DB

### Week 3 — Reliability
10. Add idempotency key check in order-service
11. Add order.failed → stock restore flow
12. Implement sale activation/expiry schedulers
13. Plug in api-gateway with your security + rate-limiter libraries

### Week 4 — Waitlist + Notifications
14. Implement waitlist-service (Redis sorted set)
15. Implement restock → waitlist notify flow
16. Wire notification-service with your communication-module
17. Verify emails appear in Maildev at localhost:1080

### Week 5 — Observability + Load Test
18. Add Micrometer counters: buys attempted, succeeded, rejected, sold-out hits
19. Set up Grafana dashboard (import ID 17175)
20. Run k6 load test — verify zero overselling in DB
21. Write README with architecture diagram and load test results screenshot

---

## Resume Bullet Points (after completion)

- Built a flash sale backend handling 500+ concurrent users with zero overselling using Redis atomic DECR operations and Kafka-based async order draining
- Implemented per-user purchase limits and FIFO waitlist using Redis Sorted Sets, notifying users on restock via pluggable communication module
- Integrated self-built rate limiter and JWT security libraries as Maven dependencies into API gateway, enforcing 50 req/min per client with zero controller changes
- Verified correctness under load using k6 — 500 concurrent users, stock never went negative across 10,000 requests
