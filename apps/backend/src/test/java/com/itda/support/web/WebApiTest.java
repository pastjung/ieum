package com.itda.support.web;

import com.itda.support.security.SecuredTestEndpoints;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 Tomcat을 띄워 HTTP로 검증한다. MockMvc로는 /error dispatch·sendError·TRACE 같은 컨테이너 동작이 재현되지 않기 때문이다.
 *
 * <p>같은 구성을 쓰는 테스트끼리 애플리케이션 context를 공유한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({WebTestEndpoints.class, SecuredTestEndpoints.class})
public @interface WebApiTest {}
