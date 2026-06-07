# Payment Service

Payment Service is a Spring Boot microservice responsible for processing payment requests in the ReserveX Ticket Booking System.

It consumes payment requests from Kafka, processes payments, persists payment records, and publishes payment results back to Kafka using the Transactional Outbox Pattern.

---

## Tech Stack

* Java 21
* Spring Boot
* Spring Data JPA
* MySQL
* Apache Kafka
* Docker
* Maven

---

## Architecture

```text
Booking Service
       |
       |
       v
payment-requested-topic
       |
       v
+------------------+
|  Payment Service |
+------------------+
       |
       | Create Payment
       | Process Payment
       | Save Outbox Event
       |
       v
outbox_events
       |
       | Scheduler
       v
+------------------+
| Outbox Publisher |
+------------------+
       |
       +------------------------+
       |                        |
       v                        v

payment-succeeded-topic   payment-failed-topic

       |                        |
       +------------+-----------+
                    |
                    v

             Booking Service
```

---

## Payment Flow

### Step 1

Booking Service publishes:

```json
{
  "eventType": "PAYMENT_REQUESTED",
  "bookingId": 501,
  "userId": 101,
  "amount": 2400
}
```

to:

```text
payment-requested-topic
```

---

### Step 2

Payment Service consumes the event.

Consumer:

```java
PaymentRequestedConsumer
```

delegates processing to:

```java
PaymentService
```

---

### Step 3

A payment record is created.

```text
payments
---------
id = 901
booking_id = 501
status = INITIATED
```

---

### Step 4

Mock payment gateway processes the payment.

Possible outcomes:

```text
SUCCESS
FAILED
```

---

### Step 5A

If payment succeeds:

```text
Payment Status = SUCCESS
Transaction Id generated
```

A PaymentSucceededEvent is created.

---

### Step 5B

If payment fails:

```text
Payment Status = FAILED
```

A PaymentFailedEvent is created.

---

### Step 6

Instead of publishing directly to Kafka, events are stored in:

```text
outbox_events
```

Example:

```text
id = 1001
event_type = PAYMENT_SUCCEEDED
status = PENDING
```

This implements the Transactional Outbox Pattern.

---

### Step 7

Scheduler scans pending outbox events every 5 seconds.

```java
PaymentOutboxPublisher
```

---

### Step 8

OutboxEventProcessor claims the event.

```text
PENDING -> PROCESSING
```

Only one service instance can claim a specific event.

This prevents duplicate publishing when multiple Payment Service instances are running.

---

### Step 9

Event is published to Kafka.

Topics:

```text
payment-succeeded-topic
payment-failed-topic
```

---

### Step 10

If publish succeeds:

```text
PROCESSING -> SENT
```

If publish fails:

```text
PROCESSING -> PENDING
retry_count++
```

After maximum retries:

```text
status = FAILED
```

---

## Database Schema

### payments

| Column         | Description                  |
| -------------- | ---------------------------- |
| id             | Payment Id                   |
| booking_id     | Booking Id                   |
| user_id        | User Id                      |
| amount         | Payment Amount               |
| status         | INITIATED / SUCCESS / FAILED |
| transaction_id | Payment Transaction Id       |
| failure_reason | Failure Reason               |
| created_at     | Creation Time                |
| updated_at     | Last Update Time             |

Constraint:

```text
UNIQUE(booking_id)
```

Guarantees:

```text
One booking can have only one payment.
```

---

### outbox_events

| Column         | Description                          |
| -------------- | ------------------------------------ |
| id             | Outbox Event Id                      |
| aggregate_id   | Payment Id                           |
| aggregate_type | PAYMENT                              |
| event_type     | SUCCESS / FAILED                     |
| payload        | JSON Event                           |
| status         | PENDING / PROCESSING / SENT / FAILED |
| retry_count    | Retry Counter                        |
| last_error     | Last Publish Error                   |
| created_at     | Creation Time                        |
| updated_at     | Last Update Time                     |

---

## Kafka Topics

### Incoming

```text
payment-requested-topic
```

Consumed by:

```java
PaymentRequestedConsumer
```

---

### Outgoing

```text
payment-succeeded-topic
```

```text
payment-failed-topic
```

Published by:

```java
OutboxEventProcessor
```

---

## Edge Cases Handled

### Duplicate Kafka Delivery

Kafka provides at-least-once delivery.

The same PAYMENT_REQUESTED event may arrive more than once.

Protection:

```java
findByBookingId()
```

and

```text
UNIQUE(booking_id)
```

---

### Concurrent Duplicate Processing

Two duplicate Kafka events processed simultaneously.

Protection:

```text
UNIQUE(booking_id)
```

One insert succeeds.

Second insert fails safely.

---

### Payment Created But Kafka Publish Fails

Payment is already saved.

Outbox event remains:

```text
PENDING
```

Scheduler retries automatically.

No data loss occurs.

---

### Multiple Payment Service Instances

Two schedulers may pick the same outbox event.

Protection:

```text
PENDING -> PROCESSING
```

claim operation.

Only one instance can publish.

---

### Kafka Publish Failure

Retry count increases.

```text
retry_count++
```

After maximum retries:

```text
status = FAILED
```

---

## Running Locally

Start infrastructure:

```bash
docker compose up -d
```

Create database:

```sql
CREATE DATABASE payment_db;
```

Install shared module:

```bash
cd common-events
mvn clean install
```

Start Payment Service:

```bash
mvn spring-boot:run
```

---

## Future Improvements

* Real payment gateway integration (Stripe/Razorpay)
* Payment timeout handling
* Refund workflow
* Dead Letter Queue (DLQ)
* Distributed tracing
* Metrics and monitoring
* Idempotency tokens from external gateways
* Saga orchestration support

---

## Design Patterns Used

* Transactional Outbox Pattern
* Event-Driven Architecture
* Idempotent Consumer
* Retry Mechanism
* Producer-Consumer Pattern
* Database Unique Constraints for Consistency

---
