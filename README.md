# CrptApi - Java-клиент для API Честного знака

[![](https://jitpack.io/v/yuraender/crpt-api.svg)](https://jitpack.io/#yuraender/crpt-api)

Thread-safe клиент для работы с API системы маркировки "Честный знак" с поддержкой ограничения количества запросов.

## Возможности

- Создание документов для ввода товаров в оборот
- Автоматическое ограничение количества запросов к API
- Потокобезопасная реализация
- Поддержка различных интервалов ограничения (секунды, минуты и т.д.)
- Простая интеграция в существующие проекты

## Требования

- Java 11 или выше
- Доступ к API Честного знака (https://ismp.crpt.ru)

## Установка через JitPack

### Maven

1. Добавьте репозиторий JitPack в `pom.xml`:

```xml
<repositories>
    <repository>
        <id>JitPack</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

2. Добавьте зависимость:

```xml
<dependency>
    <groupId>com.github.yuraender</groupId>
    <artifactId>crpt-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

1. Добавьте репозиторий JitPack в `build.gradle.kts`:

```kotlin
allprojects {
    repositories {
        maven("https://jitpack.io")
    }
}
```

2. Добавьте зависимость:

```kotlin
dependencies {
    implementation("com.github.yuraender:crpt-api:1.0.0")
}
```

### Локальная установка

Для установки в локальный Maven репозиторий:

```bash
./gradlew publishToMavenLocal
```

## Использование

### Инициализация

```java
// Ограничение: 5 запросов в секунду
CrptApi api = new CrptApi("token", TimeUnit.SECONDS, 5);

// Ограничение: 100 запросов в минуту  
CrptApi api = new CrptApi("token", TimeUnit.MINUTES, 100);
```

### Создание документа

```java
// Создание объекта документа
GoodsIntroduceDocument document = GoodsIntroduceDocument.builder()
                .id("1234567890")
                .status(DocumentStatus.CHECKED_OK)
                .importRequest(false)
                .ownerInn("1234567890")
                .participantInn("1234567890")
                .producerInn("1234567890")
                .productionDate(LocalDate.now())
                .productionType(ProductionType.OWN_PRODUCTION)
                .products(List.of(
                        GoodsIntroduceDocument.Product.builder()
                                .ownerInn("1234567890")
                                .producerInn("1234567890")
                                .productionDate(LocalDate.now())
                                .tnvedCode("0000000000")
                                .build()
                ))
                .registrationDate(LocalDateTime.now())
                .build();
String signature = "signature";

// Отправка документа
api.

introduceGoods(ProductGroup.MILK, document, "signature");
```

### Завершение работы

```java
// Корректное завершение работы
api.shutdown();
```

## Структура документа для ввода товаров в оборот

Класс `Document` содержит основные поля для создания документа:

```java
public static class Document {

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

    // Конструкторы, геттеры, сеттеры...
}
```

## Обработка ошибок

Метод `introduceGoods` может выбрасывать следующие исключения:

- `IOException` - при ошибках сети или API
- `InterruptedException` - при прерывании потока

## Настройки ограничений

| TimeUnit | Максимальное количество запросов | Рекомендуемое использование |
|----------|----------------------------------|-----------------------------|
| SECONDS  | 5-10                             | Высокочастотные операции    |
| MINUTES  | 50-100                           | Стандартные операции        |
| HOURS    | 500-1000                         | Фоновые процессы            |

## Пример полного использования

```java
public class Main {

    public static void main(String[] args) {
        CrptApi api = new CrptApi("your_token_here", TimeUnit.SECONDS, 5);

        GoodsIntroduceDocument document = GoodsIntroduceDocument.builder()
                .id("1234567890")
                .status(DocumentStatus.CHECKED_OK)
                .importRequest(false)
                .ownerInn("1234567890")
                .participantInn("1234567890")
                .producerInn("1234567890")
                .productionDate(LocalDate.now())
                .productionType(ProductionType.OWN_PRODUCTION)
                .products(List.of(
                        GoodsIntroduceDocument.Product.builder()
                                .ownerInn("1234567890")
                                .producerInn("1234567890")
                                .productionDate(LocalDate.now())
                                .tnvedCode("0000000000")
                                .build()
                ))
                .registrationDate(LocalDateTime.now())
                .build();
        api.introduceGoods(ProductGroup.MILK, document, "signature");

        api.shutdown();
    }
}
```
