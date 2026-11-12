package com.gestaosla.notificacoes;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notificacoes;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.username=test",
        "spring.rabbitmq.password=test"
})
class NotificacoesApplicationTest {
    @Test
    void iniciaContextoComConfiguracaoExterna() {
    }
}
