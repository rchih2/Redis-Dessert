package com.gtalent.redis.dessert.dto;

import java.math.BigDecimal;

public record OrderItemResponseDTO(
        Long dessertId,
        String dessertName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal lineTotal
) {}