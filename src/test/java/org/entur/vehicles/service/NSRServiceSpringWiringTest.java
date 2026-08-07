package org.entur.vehicles.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NSRServiceAncestorTest} exercises {@code NSRService} by calling {@code new
 * NSRService(...)} directly, and {@code ApplicationGraphQlSchemaTests} replaces the bean
 * entirely with {@code @MockitoBean} - so nothing in the suite ever asks Spring to construct
 * the real bean. That is exactly the gap that let {@code NSRService} grow a second,
 * non-{@code @Autowired} constructor while every test stayed green: with two constructors and
 * neither annotated, Spring can no longer infer which one to use and fails to boot with "No
 * default constructor found" - a startup failure only a real Spring context can catch.
 * <p>
 * {@code vehicle.nsr.lookup.enabled=false} in {@code src/test/resources/application.properties}
 * keeps this hermetic: {@code warmUpCache} short-circuits without downloading or parsing a
 * NeTEx file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NSRServiceSpringWiringTest {

    @Autowired
    private NSRService nsrService;

    @Test
    void theRealNsrServiceBeanIsConstructedByTheSpringContext() {
        assertThat(nsrService).isNotNull();
    }
}
