package com.fraud.project.rules;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.fraud.project.entity.RiskAction;

/**
 * `rules/rules.yaml`'ı uygulama başlarken BİR KERE okuyup belleğe alır.
 * SnakeYAML kullanıyoruz (Jackson değil) — zaten Spring Boot'un kendisi
 * application.yml desteği için classpath'te taşıyor, bu da Jackson 2/3
 * karmaşasına hiç girmeden (bkz. MlServiceConfig'teki Jackson 3 geçiş
 * notları) basit bir Map<String,Object> ağacı üretiyor.
 */
@Component
public class RuleDefinitionLoader {

    private static final String RULES_PATH = "rules/rules.yaml";

    private final List<RuleDefinition> rules;

    public RuleDefinitionLoader() {
        try (InputStream in = new ClassPathResource(RULES_PATH).getInputStream()) {
            this.rules = parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Kural dosyası okunamadı: " + RULES_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<RuleDefinition> parse(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root.get("rules");

        return rawRules.stream()
            .map(r -> new RuleDefinition(
                (String) r.get("name"),
                (String) r.get("description"),
                (String) r.get("condition"),
                RiskAction.valueOf((String) r.get("action"))
            ))
            .toList();
    }

    public List<RuleDefinition> findAll() {
        return rules;
    }
}
