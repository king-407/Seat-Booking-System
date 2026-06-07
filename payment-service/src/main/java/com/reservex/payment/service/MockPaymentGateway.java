package com.reservex.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MockPaymentGateway {

    @Value("${app.payment.mock-result}")
    private String mockResult;

    public boolean chargePayment() {
        return "SUCCESS".equalsIgnoreCase(mockResult);
    }
}