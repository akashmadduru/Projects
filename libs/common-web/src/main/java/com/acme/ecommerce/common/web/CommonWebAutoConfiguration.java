package com.acme.ecommerce.common.web;

import com.acme.ecommerce.common.core.error.ProblemPayloadFactory;
import com.acme.ecommerce.common.core.time.SystemTimeProvider;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.web.audit.AuditInterceptor;
import com.acme.ecommerce.common.web.audit.AuditPublisher;
import com.acme.ecommerce.common.web.correlation.CorrelationIdFilter;
import com.acme.ecommerce.common.web.error.GlobalExceptionHandler;
import com.acme.ecommerce.common.web.error.SecurityProblemExceptionHandler;
import com.acme.ecommerce.common.web.feign.CorrelationAndAuthForwardingInterceptor;
import com.acme.ecommerce.common.web.job.JobLock;
import jakarta.servlet.Servlet;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the shared web edge.
 *
 * <p>Registers the correlation-id filter, the problem+json advices and the Feign header-forwarding
 * interceptor, so a service gets consistent error handling and tracing by depending on this module
 * - with no copy-pasted {@code @Configuration} in each service.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service that needs different behaviour
 * overrides it by defining its own bean of the same type rather than by disabling this class.
 */
@AutoConfiguration
@ConditionalOnClass({Servlet.class, org.springframework.web.servlet.DispatcherServlet.class})
public class CommonWebAutoConfiguration {

  /** The platform clock. Domain code injects {@link TimeProvider}, never {@code Instant.now()}. */
  @Bean
  @ConditionalOnMissingBean(TimeProvider.class)
  public TimeProvider timeProvider() {
    return new SystemTimeProvider();
  }

  @Bean
  @ConditionalOnMissingBean(ProblemPayloadFactory.class)
  public ProblemPayloadFactory problemPayloadFactory(TimeProvider timeProvider) {
    return new ProblemPayloadFactory(timeProvider);
  }

  /** Cluster-wide guard for {@code @Scheduled} jobs; see {@link JobLock}. */
  @Bean
  @ConditionalOnMissingBean(JobLock.class)
  public JobLock jobLock(ObjectProvider<DataSource> dataSource) {
    return new JobLock(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean(GlobalExceptionHandler.class)
  public GlobalExceptionHandler globalExceptionHandler(
      ProblemPayloadFactory problemPayloadFactory) {
    return new GlobalExceptionHandler(problemPayloadFactory);
  }

  /**
   * Registered through a {@link FilterRegistrationBean} rather than as a bare {@code Filter} bean
   * so the order is explicit. Ordering matters here: the filter has to run before Spring Security,
   * or authentication failures get logged with no correlation id.
   */
  @Bean
  @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
  public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(CorrelationIdFilter.ORDER);
    registration.addUrlPatterns("/*");
    registration.setName("correlationIdFilter");
    return registration;
  }

  /**
   * Security-aware advice, registered only when spring-security is on the classpath. See {@link
   * SecurityProblemExceptionHandler} for why it cannot live in the main advice.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(org.springframework.security.access.AccessDeniedException.class)
  public static class SecurityProblemConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityProblemExceptionHandler.class)
    public SecurityProblemExceptionHandler securityProblemExceptionHandler(
        ProblemPayloadFactory problemPayloadFactory) {
      return new SecurityProblemExceptionHandler(problemPayloadFactory);
    }
  }

  /**
   * Admin/seller write auditing (Phase 10) to the {@code audit.events} topic. Registered only when
   * Kafka clients, Spring Security and MVC are present; {@code audit.enabled=false} turns it off.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({
    org.apache.kafka.clients.producer.KafkaProducer.class,
    org.springframework.security.core.context.SecurityContextHolder.class,
    org.springframework.web.servlet.config.annotation.WebMvcConfigurer.class
  })
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "audit.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public static class AuditConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AuditPublisher.class)
    public AuditPublisher auditPublisher(org.springframework.core.env.Environment env) {
      return new AuditPublisher(
          env.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"),
          env.getProperty("spring.application.name", "service"));
    }

    @Bean
    public org.springframework.web.servlet.config.annotation.WebMvcConfigurer auditWebMvcConfigurer(
        AuditPublisher publisher) {
      return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
        @Override
        public void addInterceptors(
            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
          registry.addInterceptor(new AuditInterceptor(publisher)).addPathPatterns("/api/**");
        }
      };
    }
  }

  /** Feign header relay, registered only when a Feign client stack is present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(feign.RequestInterceptor.class)
  public static class FeignPropagationConfiguration {

    @Bean
    @ConditionalOnMissingBean(CorrelationAndAuthForwardingInterceptor.class)
    public CorrelationAndAuthForwardingInterceptor correlationAndAuthForwardingInterceptor() {
      return new CorrelationAndAuthForwardingInterceptor();
    }
  }
}
