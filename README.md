# ⚡ Flash Sale Backend System

A robust, highly concurrent, and distributed microservices backend built with **Java Spring Boot**, designed to handle massive traffic spikes during time-bounded flash sales. The system guarantees **zero overselling**, fair queue-based order processing, waitlist handling, and real-time notifications.

---

## 🏗️ Architecture Overview

The system is built using an event-driven microservices architecture to decouple the fast path (stock reservation) from the slow path (database persistence), ensuring ultra-low latency under extreme loads.

### Microservices
- **`api-gateway` (Port: 8080)**: Central entry point. Handles routing, JWT authentication (via `ReusableSecurityModule`), and aggressive burst traffic rate-limiting (via `reusable-rate-limiter`).
- **`sale-service` (Port: 8081)**: The core engine. Manages flash sale lifecycles and utilizes **Redis atomic operations** (`DECR`/`INCR`) for distributed stock counting to prevent race conditions. Pushes successful reservations to Kafka.
- **`order-service` (Port: 8082)**: Asynchronously drains the Kafka order queue, performs idempotency checks, and safely persists confirmed orders to PostgreSQL.
- **`waitlist-service` (Port: 8083)**: Manages overflow traffic when items sell out. Uses **Redis Sorted Sets (ZSET)** to maintain a fair, timestamp-based queue for sold-out items.
- **`notification-service` (Port: 8084)**: Consumes Kafka events to send purchase confirmations and waitlist restock alerts via email/SMS (via `reusable-sms-email-service-module`).

---

## ✨ Key Features & Technical Highlights

* **Zero Overselling Guarantee**: Instead of naive DB locks, the system uses single-threaded Redis atomic operations (`DECR`) to reserve stock instantly, completely eliminating race conditions when thousands of users hit the "Buy" button simultaneously.
* **Event-Driven Resilience**: Apache Kafka decouples services. If the database struggles under load, the `sale-service` continues accepting requests seamlessly, while the `order-service` persists them at a controlled pace.
* **Fair Waitlist System**: Users who miss out are queued in Redis. If orders fail or administrators restock items, the system automatically pops the oldest entries from the queue and notifies them.
* **Idempotent Order Processing**: Prevents double-charging or duplicate orders during network retries using unique idempotency keys.
* **Comprehensive Monitoring**: Integrated with Prometheus and Grafana for real-time observability of system health and metrics.

---

## 🛠️ Technology Stack

* **Language**: Java 17
* **Framework**: Spring Boot 3.2.0, Spring Cloud Gateway
* **Databases**: PostgreSQL 15 (Persistent Storage), Redis 7 (Atomic Counters, Rate Limiting, Distributed Locks, Waitlist)
* **Message Broker**: Apache Kafka 7.5.0 & Zookeeper
* **Monitoring**: Prometheus, Grafana
* **Testing**: k6 (Load Testing), MailDev (Local SMTP Testing)
* **Containerization**: Docker & Docker Compose

---

## 🚀 Getting Started (Local Setup)

### Prerequisites
* Java 17
* Maven 3.8+
* Docker & Docker Compose

### 1. Start Infrastructure Services
The project includes a `docker-compose.yml` file that spins up all necessary infrastructural dependencies (Postgres, Redis, Kafka, Zookeeper, Prometheus, Grafana, and MailDev).

```bash
docker-compose up -d
```

### 2. Build the Project
Compile the parent POM and all microservices:
```bash
mvn clean install -DskipTests
```

### 3. Run Microservices
You can run the microservices using your IDE or via Maven. Ensure they are started in the following logical order (though Eureka/Service Discovery is not explicitly required, Gateway needs the downstream services):
1. `sale-service` (8081)
2. `order-service` (8082)
3. `waitlist-service` (8083)
4. `notification-service` (8084)
5. `api-gateway` (8080)

### 4. Monitoring & Tooling Interfaces
Once running, you can access the following local dashboards:
* **MailDev (Email Testing)**: [http://localhost:1080](http://localhost:1080)
* **Grafana**: [http://localhost:3000](http://localhost:3000) (Credentials: `admin` / `admin`)
* **Prometheus**: [http://localhost:9090](http://localhost:9090)

---

## ⚙️ Core Request Flows

### The "Buy" Endpoint Flowchart

This flowchart illustrates the complete decision tree and lifecycle of a request when a user hits the `/buy` endpoint, including both the successful path and the sold-out/waitlist fallback.

```mermaid
graph TD
    User((User)) -->|POST /api/sales/{id}/buy| Gateway(API Gateway)
    
    Gateway -->|Rate Limit & Auth Check| SaleSvc(Sale Service)
    
    SaleSvc -->|Atomic DECR stock| Redis[(Redis)]
    
    Redis -- Returns New Stock Value --> StockCheck{Stock >= 0?}
    
    %% Happy Path
    StockCheck -->|Yes| QueueOrder[Publish to Kafka: sale.order.requested]
    QueueOrder --> Return200[Return 200 OK: Order Queued]
    
    QueueOrder -.->|Async Consume| OrderSvc(Order Service)
    OrderSvc -->|Idempotency Check & Persist| DB[(PostgreSQL)]
    OrderSvc -->|Publish| Confirm[Kafka: order.confirmed]
    Confirm -.->|Async Consume| NotifySvc(Notification Service)
    NotifySvc -->|Send Email| User
    
    %% Sold Out Path
    StockCheck -->|No| Compensate[INCR stock to compensate]
    Compensate --> Return409[Return 409 SOLD_OUT]
    
    Return409 -.->|User Opts In| WaitlistReq[POST /api/sales/{id}/waitlist]
    WaitlistReq --> WaitlistSvc(Waitlist Service)
    WaitlistSvc -->|ZADD User with Timestamp| Redis
    WaitlistSvc --> ReturnWaitlist[Return 200 OK: Waitlisted]
    
    classDef success fill:#d4edda,stroke:#28a745,stroke-width:2px;
    classDef error fill:#f8d7da,stroke:#dc3545,stroke-width:2px;
    class Return200 success;
    class Return409 error;
```

### 1. Happy Path (Successful Purchase)
1. User requests to buy via `api-gateway` (Rate limits and JWT checked).
2. `sale-service` performs atomic `DECR` in Redis. If stock >= 0, reservation succeeds.
3. `sale-service` pushes an `OrderRequestEvent` to the Kafka topic `sale.order.requested` and returns success to the user immediately.
4. `order-service` consumes the event, checks idempotency, and persists the order to Postgres.
5. `order-service` pushes to `order.confirmed` Kafka topic.
6. `notification-service` consumes the confirmation and sends an email.

### 2. Sold Out & Waitlist Path
1. User attempts to buy. `sale-service`'s Redis `DECR` returns `< 0`.
2. `sale-service` instantly compensates with an `INCR` and returns a `409 SOLD_OUT`.
3. User opts into the waitlist via `waitlist-service`.
4. User ID is added to a Redis Sorted Set (`ZADD`) with the current timestamp as the score.

### 3. Restock Path
1. Admin triggers a restock via `sale-service`.
2. `sale-service` increments Redis stock and publishes a `sale.restocked` event.
3. `waitlist-service` pops the top `N` users from the Redis waitlist (`ZPOPMIN`).
4. Notifications are dispatched inviting waitlisted users to buy.

---

## 🚦 Load Testing

The system is designed to be battle-tested against high concurrency. A k6 script (`load-test.js`) is included in the root directory to simulate thousands of concurrent buyers.

```bash
k6 run load-test.js
```
This ensures that the "Zero Overselling" logic holds up perfectly under stress.
