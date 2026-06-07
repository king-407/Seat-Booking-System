# ReserveX - Distributed Ticket Booking System

ReserveX is a distributed ticket booking platform built using Spring Boot microservices, Kafka, Redis, and MySQL.

The project focuses on solving real-world distributed systems problems such as seat locking, idempotency, reliable event delivery, duplicate event processing, concurrent booking conflicts, and eventual consistency.

---

# Tech Stack

### Backend

* Java 21
* Spring Boot
* Spring Data JPA
* Hibernate
* Maven

### Databases

* MySQL

### Messaging

* Apache Kafka

### Caching / Distributed Coordination

* Redis

### Infrastructure

* Docker
* Docker Compose

---

# System Architecture

```text
+------------------+
|  Booking Service |
+------------------+
         |
         |
         | PAYMENT_REQUESTED
         v

+------------------+
|  Kafka Topics    |
+------------------+

payment-requested-topic

         |
         v

+------------------+
|  Payment Service |
+------------------+

         |
         |
         +------------------------+
         |                        |
         v                        v

payment-succeeded-topic   payment-failed-topic

         |                        |
         +------------+-----------+
                      |
                      v

+------------------+
|  Booking Service |
+------------------+
```

---

# Microservices

## 1. Booking Service

Responsible for:

* Seat reservation
* Redis seat locking
* Booking lifecycle management
* Payment initiation
* Booking expiration
* Confirmed seat management
* Payment event handling

### Database Tables

```text
bookings
booking_seats
confirmed_seats
outbox_events
```

---

## 2. Payment Service

Responsible for:

* Consuming payment requests
* Payment processing
* Payment status management
* Publishing payment result events

### Database Tables

```text
payments
outbox_events
```

---

## 3. Common Events Module

Shared library used by all services.

Contains:

```text
Kafka Event DTOs
Shared Enums
Event Contracts
```

Examples:

```text
PaymentRequestedEvent
PaymentSucceededEvent
PaymentFailedEvent
```

---

# Booking Flow

## Step 1

User selects seats.

Example:

```text
Trip = 9001
Seats = A1, A2
Amount = 2400
```

Request:

```http
POST /bookings
```

---

## Step 2

Booking Service acquires Redis locks.

Example:

```text
seat_lock:trip:9001:seat:A1
seat_lock:trip:9001:seat:A2
```

Redis stores:

```text
A1 -> lockToken-123
A2 -> lockToken-123
```

Locks automatically expire after 5 minutes.

---

## Step 3

Booking is created.

```text
Booking Status = PENDING
```

Rows created:

```text
bookings
booking_seats
```

---

## Step 4

User clicks Pay.

```http
POST /bookings/{id}/payment
```

Booking Service publishes:

```text
PAYMENT_REQUESTED
```

using Transactional Outbox Pattern.

---

## Step 5

Payment Service consumes the event.

Payment record created:

```text
INITIATED
```

---

## Step 6

Mock payment gateway processes payment.

Possible outcomes:

```text
SUCCESS
FAILED
```

---

## Step 7A

Success:

```text
PAYMENT_SUCCEEDED
```

event is published.

---

## Step 7B

Failure:

```text
PAYMENT_FAILED
```

event is published.

---

## Step 8

Booking Service consumes payment result.

### Success

Booking:

```text
CONFIRMED
```

Booking Seats:

```text
CONFIRMED
```

Rows created:

```text
confirmed_seats
```

Redis locks released.

---

### Failure

Booking:

```text
CANCELLED
```

Booking Seats:

```text
CANCELLED
```

Redis locks released.

---

# Database Design

## bookings

Stores booking lifecycle.

Possible states:

```text
PENDING
PAYMENT_REQUESTED
CONFIRMED
CANCELLED
EXPIRED
REFUND_REQUIRED
```

---

## booking_seats

Stores seats selected by a booking.

Possible states:

```text
PENDING
CONFIRMED
CANCELLED
EXPIRED
```

---

## confirmed_seats

Final source of truth.

Constraint:

```text
UNIQUE(trip_id, seat_number)
```

Guarantees:

```text
No seat can ever be sold twice.
```

---

## payments

Stores payment records.

Constraint:

```text
UNIQUE(booking_id)
```

Guarantees:

```text
One booking can have only one payment.
```

---

# Distributed Systems Concepts Implemented

## 1. Redis Distributed Seat Locking

Problem:

```text
Two users trying to book the same seat simultaneously.
```

Solution:

```text
Redis SETNX + TTL
```

Only one user acquires the seat lock.

---

## 2. Lock Ownership Protection

Problem:

```text
Expired booking deletes another booking's lock.
```

Solution:

```text
Unique lockToken per booking.
```

Redis stores:

```text
seat -> lockToken
```

instead of:

```text
seat -> LOCKED
```

---

## 3. Atomic Lock Release

Problem:

```text
GET
COMPARE
DELETE
```

is not atomic.

Solution:

Redis Lua Script:

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

Guarantees:

```text
Delete lock only if current owner matches.
```

---

## 4. Idempotent Booking Creation

Problem:

```text
User clicks Book multiple times.
```

Solution:

```text
idempotency_key
```

Constraint:

```text
UNIQUE(idempotency_key)
```

Guarantees:

```text
Same booking request is processed once.
```

---

## 5. Idempotent Payment Processing

Problem:

```text
Duplicate Kafka events.
```

Solution:

```text
UNIQUE(booking_id)
```

Guarantees:

```text
Payment processed once.
```

---

## 6. Transactional Outbox Pattern

Problem:

```text
Database save succeeds.
Kafka publish fails.
```

Solution:

```text
Store event in outbox table first.
```

Scheduler publishes later.

Guarantees:

```text
No event loss.
```

---

## 7. Kafka At-Least-Once Delivery

System assumes:

```text
Messages may be delivered more than once.
```

Consumers are designed to be idempotent.

---

# Failure Scenarios Handled

### Duplicate Booking Requests

Handled via:

```text
idempotency_key
```

---

### Duplicate Kafka Messages

Handled via:

```text
UNIQUE constraints
Idempotent consumers
```

---

### Booking Expiry

Locks expire automatically after 5 minutes.

Booking status:

```text
EXPIRED
```

---

### Payment Failure

Booking:

```text
CANCELLED
```

Locks released.

---

### Payment Success After Expiry

Booking:

```text
REFUND_REQUIRED
```

No seat confirmation occurs.

---

### Kafka Publish Failure

Outbox event retried automatically.

---

### Multiple Service Instances

Outbox events are claimed using:

```text
PENDING -> PROCESSING
```

state transition.

Only one instance can publish a specific event.

---

# Running Locally

## Start Infrastructure

```bash
docker compose up -d
```

---

## Create Databases

```sql
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;
```

---

## Install Shared Module

```bash
cd common-events
mvn clean install
```

---

## Start Booking Service

```bash
cd booking-service
mvn spring-boot:run
```

---

## Start Payment Service

```bash
cd payment-service
mvn spring-boot:run
```

---

# Future Improvements

* Saga Orchestration
* Refund Service
* Notification Service
* DLQ Topics
* Prometheus Metrics
* Grafana Dashboards
* OpenTelemetry Tracing
* API Gateway
* Authentication & Authorization
* Kubernetes Deployment
* Seat Availability Search Service

---

# Key Learnings

* Event-Driven Architecture
* Distributed Locking
* Transactional Outbox Pattern
* Kafka Reliability Patterns
* Idempotency
* Eventual Consistency
* Concurrent Booking Handling
* Fault Tolerant System Design
