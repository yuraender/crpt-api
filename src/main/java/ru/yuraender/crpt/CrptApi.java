package ru.yuraender.crpt;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final ObjectMapper objectMapper;

    static {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper = mapper;
    }

    private final String token;
    private final HttpClient httpClient;
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;

    /**
     * Клиент для взаимодействия с API Честного Знака.
     * Предоставляет методы для работы с системой маркировки товаров.
     *
     * @param token        токен авторизации для доступа к API
     * @param timeUnit     единица времени для ограничения запросов
     * @param requestLimit максимальное количество запросов в указанный промежуток времени
     */
    public CrptApi(String token, TimeUnit timeUnit, int requestLimit) {
        this.token = token;
        this.httpClient = HttpClient.newHttpClient();
        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Периодическое освобождение семафора
        scheduler.scheduleAtFixedRate(() -> {
            int availablePermits = semaphore.availablePermits();
            if (availablePermits < requestLimit) {
                semaphore.release(requestLimit - availablePermits);
            }
        }, 0L, 1L, timeUnit);
    }

    /**
     * Отправляет запрос на ввод товаров в оборот.
     *
     * @param productGroup группа товаров
     * @param document     документ с информацией о товарах
     * @param signature    электронная подпись документа
     * @return уникальный идентификатор документа в ИС МП
     * @throws RuntimeException если произошла ошибка при выполнении запроса
     */
    public String introduceGoods(ProductGroup productGroup, IDocument document, String signature) {
        try {
            semaphore.acquire();

            ObjectNode bodyNode = objectMapper.createObjectNode();
            bodyNode.put("document_format", "MANUAL");
            bodyNode.put("product_document", new String(
                    Base64.getEncoder().encode(document.toString().getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
            ));
            bodyNode.put("product_group", productGroup.getName());
            bodyNode.put("signature", new String(
                    Base64.getEncoder().encode(signature.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
            ));
            bodyNode.put("type", "LP_INTRODUCE_GOODS");
            String body = objectMapper.writeValueAsString(bodyNode);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create?pg=" + productGroup.getName()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(3L))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("API request failed: " + response.body());
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            if (!rootNode.has("value")) {
                throw new RuntimeException("Field 'value' not found in JSON response");
            }
            return rootNode.get("value").asText();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to send request", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread was interrupted", ex);
        }
    }

    /**
     * Корректно завершает работу API клиента, освобождая ресурсы.
     */
    public synchronized void shutdown() {
        if (scheduler.isShutdown()) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5L, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /**
     * Группы товаров.
     */
    @Getter
    @RequiredArgsConstructor
    public enum ProductGroup {

        /** Предметы одежды, белье постельное, столовое, туалетное и кухонное */
        CLOTHES(1, "clothes"),
        /** Обувные товары */
        SHOES(2, "shoes"),
        /** Табачная продукция */
        TOBACCO(3, "tobacco"),
        /** Духи и туалетная вода */
        PERFUMERY(4, "perfumery"),
        /** Шины и покрышки пневматические резиновые новые */
        TIRES(5, "tires"),
        /** Фотокамеры (кроме кинокамер), фотовспышки и лампы-вспышки */
        ELECTRONICS(6, "electronics"),
        /** Лекарственные препараты для медицинского применения */
        PHARMA(7, "pharma"),
        /** Молочная продукция */
        MILK(8, "milk"),
        /** Велосипеды и велосипедные рамы */
        BICYCLE(9, "bicycle"),
        /** Кресла-коляски */
        WHEELCHAIRS(10, "wheelchairs");

        private final int id;
        private final String name;
    }

    public interface ICrptObject {

        /**
         * Выполняет валидацию объекта.
         *
         * @throws IllegalArgumentException если объект невалиден
         */
        void validate();
    }

    /**
     * Документ системы маркировки.
     */
    public interface IDocument extends ICrptObject {

        /**
         * Возвращает тип документа.
         *
         * @return тип документа
         */
        DocumentType getType();
    }

    /**
     * Товар системы маркировки.
     */
    public interface IProduct extends ICrptObject {

    }

    /**
     * Документ ввода товаров в оборот.
     * Используется для регистрации факта производства или импорта товаров.
     */
    @Builder
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class GoodsIntroduceDocument implements IDocument {

        @JsonProperty("doc_id")
        private String id;                          // Идентификатор документа
        @JsonProperty("doc_status")
        private DocumentStatus status;              // Статус документа
        @JsonProperty("importRequest")
        private Boolean importRequest;              // Признак импорта

        private String ownerInn;                    // ИНН собственника товара
        private String participantInn;              // ИНН участника оборота товара
        private String producerInn;                 // ИНН производителя товара

        @JsonFormat(
                shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd"
        )
        private LocalDate productionDate;           // Дата производства товара
        private ProductionType productionType;      // Тип производственного заказа
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private Collection<Product> products;       // Перечень товаров

        @JsonFormat(
                shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss"
        )
        @JsonProperty("reg_date")
        private LocalDateTime registrationDate;     // Дата и время регистрации
        @JsonProperty("reg_number")
        private String registrationNumber;          // Регистрационный номер документа

        /** Тип документа */
        @JsonProperty("doc_type")
        public DocumentType getType() {
            return DocumentType.LP_INTRODUCE_GOODS;
        }

        /**
         * Выполняет валидацию документа и всех вложенных товаров.
         *
         * @throws IllegalArgumentException если документ невалиден
         */
        @Override
        public void validate() {
            check(id != null, "id is null");
            check(!id.isBlank() && id.length() <= 255, "id is empty or is too long");
            check(status != null, "status is null");
            check(ownerInn != null, "ownerInn is null");
            check(ownerInn.length() == 10, "ownerInn must be 10 characters long");
            check(participantInn != null, "participantInn is null");
            check(participantInn.length() == 10, "participantInn must be 10 characters long");
            check(producerInn != null, "producerInn is null");
            check(producerInn.length() == 10, "producerInn must be 10 characters long");
            check(productionDate != null, "productionDate is null");
            check(productionType != null, "productionType is null");
            check(products != null, "products is null");
            for (Product product : products) {
                product.validate();
            }
            check(registrationDate != null, "registrationDate is null");
            if (registrationNumber != null) {
                check(!registrationNumber.isBlank(), "registrationNumber is empty");
            }
        }

        /**
         * Преобразует документ в JSON строку.
         *
         * @return JSON представление документа
         * @throws RuntimeException если преобразование не удалось
         */
        @Override
        public String toString() {
            validate();
            try {
                return objectMapper.writeValueAsString(this);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Информация о товаре в документе ввода в оборот.
         */
        @Builder
        @Getter
        @AllArgsConstructor(access = AccessLevel.PRIVATE)
        public static class Product implements IProduct {

            private CertificateDocument certificateDocument; // Код вида документа обязательной сертификации
            @JsonFormat(
                    shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd"
            )
            private LocalDate certificateDocumentDate;       // Дата документа обязательной сертификации
            private String certificateDocumentNumber;        // Номер документа обязательной сертификации
            private String ownerInn;                         // ИНН собственника
            private String producerInn;                      // ИНН производителя
            @JsonFormat(
                    shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd"
            )
            private LocalDate productionDate;                // Дата производства
            private String tnvedCode;                        // Код товарной номенклатуры
            private String uitCode;                          // Уникальный идентификатор товара
            private String uituCode;                         // Уникальный идентификатор транспортной упаковки

            /**
             * Выполняет валидацию товара.
             *
             * @throws IllegalArgumentException если товар невалиден
             */
            @Override
            public void validate() {
                if (certificateDocument != null
                        || certificateDocumentDate != null || certificateDocumentNumber != null) {
                    check(certificateDocument != null, "certificateDocument is null");
                    check(certificateDocumentDate != null, "certificateDocumentDate is null");
                    check(certificateDocumentNumber != null, "certificateDocumentNumber is null");
                }
                check(ownerInn != null, "ownerInn is null");
                check(ownerInn.length() == 10, "ownerInn must be 10 characters long");
                check(producerInn != null, "producerInn is null");
                check(producerInn.length() == 10, "producerInn must be 10 characters long");
                check(productionDate != null, "productionDate is null");
                check(tnvedCode != null, "tnvedCode is null");
                check(tnvedCode.length() == 10, "tnvedCode must be 10 characters long");
                if (uitCode != null) {
                    check(!uitCode.isEmpty(), "uitCode is empty");
                }
                if (uituCode != null) {
                    check(!uituCode.isEmpty(), "uituCode is empty");
                }
            }

            /**
             * Преобразует товар в JSON строку.
             *
             * @return JSON представление товара
             * @throws RuntimeException если преобразование не удалось
             */
            @Override
            public String toString() {
                validate();
                try {
                    return objectMapper.writeValueAsString(this);
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    /**
     * Статусы документов в системе маркировки.
     */
    public enum DocumentStatus {

        /** Проверяется */
        CHECKED_OK,
        /** Успешно обработан */
        CHECKED_NOT_OK,
        /** Обработан с ошибками */
        PROCESSING_ERROR,
        /** Ошибка при обработке */
        IN_PROGRESS
    }

    /**
     * Типы документов в системе маркировки.
     */
    public enum DocumentType {

        /** Ввод в оборот. Производство РФ. JSON */
        LP_INTRODUCE_GOODS
    }

    /**
     * Типы производства товаров.
     */
    public enum ProductionType {

        /** Собственное производство */
        OWN_PRODUCTION,
        /** Производство товара по договору */
        CONTRACT_PRODUCTION
    }

    /**
     * Типы документов сертификации.
     */
    public enum CertificateDocument {

        /** Сертификат соответствия */
        CONFORMITY_CERTIFICATE,
        /** Декларация соответствия */
        CONFORMITY_DECLARATION
    }

    /**
     * Вспомогательный метод для проверки условий.
     *
     * @param test условие для проверки
     * @param message сообщение об ошибке если условие ложно
     * @throws IllegalArgumentException если условие ложно
     */
    private static void check(boolean test, String message) {
        if (!test) {
            throw new IllegalArgumentException(message);
        }
    }
}
