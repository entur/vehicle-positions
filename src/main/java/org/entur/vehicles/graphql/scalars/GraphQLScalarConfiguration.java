package org.entur.vehicles.graphql.scalars;

import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class GraphQLScalarConfiguration {

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(dateTimeScalar())
                .scalar(durationScalar());
    }

    @Bean
    public GraphQLScalarType dateTimeScalar() {
        return DateScalarConfiguration.dateScalar();
    }
    @Bean
    public GraphQLScalarType durationScalar() {
        return DurationScalarConfiguration.durationScalar();
    }
}