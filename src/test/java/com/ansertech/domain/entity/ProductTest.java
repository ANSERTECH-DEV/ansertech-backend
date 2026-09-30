package com.ansertech.domain.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void redondeaElPrecioAlEnteroMasCercano() {
        assertThat(rounded("4555.93")).isEqualTo(new BigDecimal("4556"));
        assertThat(rounded("1357.12")).isEqualTo(new BigDecimal("1357"));
        assertThat(rounded("185.50")).isEqualTo(new BigDecimal("186"));   // .50 sube
        assertThat(rounded("100")).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void precioNuloSeMantieneNulo() {
        Product p = Product.builder().sku("X").name("X").build();
        p.roundUnitPrice();
        assertThat(p.getUnitPrice()).isNull();
    }

    private static BigDecimal rounded(String price) {
        Product p = Product.builder().sku("X").name("X").unitPrice(new BigDecimal(price)).build();
        p.roundUnitPrice();
        return p.getUnitPrice();
    }
}
