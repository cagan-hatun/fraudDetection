package com.fraud.project.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * `fixtures/demo_transactions.json`'ı uygulama başlarken BİR KERE okuyup
 * belleğe alır. Spring Boot 4.x'in autoconfigure ettiği (Jackson 3 —
 * `tools.jackson`, eski `com.fasterxml.jackson.databind` DEĞİL) ObjectMapper
 * bean'i enjekte ediliyor; java.time (OffsetDateTime) desteği Jackson 3'te
 * databind'e gömülü, ayrı bir modül kaydına gerek yok.
 */
@Component
public class DemoFixtureLoader {

    private static final String FIXTURES_PATH = "fixtures/demo_transactions.json";

    private final List<DemoTransactionFixture> fixtures;

    public DemoFixtureLoader(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(FIXTURES_PATH).getInputStream()) {
            this.fixtures = parse(objectMapper, in);
        } catch (IOException e) {
            throw new IllegalStateException("Demo fixture'ları okunamadı: " + FIXTURES_PATH, e);
        }
    }

    /**
     * Asıl parse mantığı — package-private, JSON'un doğru DemoTransactionFixture
     * listesine dönüştüğünü ClassPathResource/dosya sistemine dokunmadan (bellek
     * içi bir JSON string'iyle) test edebilmek için constructor'dan ayrıldı.
     * Jackson 3'te readValue artık checked IOException değil, unchecked
     * JacksonException fırlatıyor — bozuk bir fixture dosyası uygulamayı
     * (bilinçli olarak) başlangıçta çökertir.
     */
    static List<DemoTransactionFixture> parse(ObjectMapper objectMapper, InputStream in) {
        return objectMapper.readValue(in, new TypeReference<List<DemoTransactionFixture>>() {});
    }

    public List<DemoTransactionFixture> findAll() {
        return fixtures;
    }

    public Optional<DemoTransactionFixture> findByScenarioId(String scenarioId) {
        return fixtures.stream()
            .filter(f -> f.scenarioId().equals(scenarioId))
            .findFirst();
    }
}
