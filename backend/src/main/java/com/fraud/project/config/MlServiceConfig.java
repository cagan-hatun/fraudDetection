package com.fraud.project.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class MlServiceConfig {

    @Bean
    RestClient mlServiceRestClient(@Value("${ml-service.base-url}") String baseUrl) {
        // İki ayrı düzeltme gerekti:
        // 1) Boot 4.1.1'de RestClient.Builder autoconfigure edilmiyor; çıplak
        //    RestClient.builder()'ın varsayılan dönüştürücüleri bir Map body'sini
        //    JSON'a serileştiremiyordu (hatasız, sessizce boş body) — Jackson 3
        //    tabanlı JSON dönüştürücüyü açıkça ekliyoruz.
        // 2) Varsayılan JDK HttpClient, düz HTTP/1.1 üzerinden bir HTTP/2 (h2c)
        //    upgrade denemesi yapıyor — uvicorn (ml-service) bunu desteklemiyor
        //    ("Unsupported upgrade request"), bağlantı bozulup sonraki istek
        //    "header parser received no bytes" hatasıyla patlıyor. HttpClient'ı
        //    HTTP/1.1'e sabitleyerek çözülüyor.
        HttpClient http1Client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(new JdkClientHttpRequestFactory(http1Client))
            .messageConverters(converters -> converters.add(0, new JacksonJsonHttpMessageConverter()))
            .build();
    }
}
