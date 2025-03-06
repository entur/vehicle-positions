package org.entur.vehicles.graphql.scalars;

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.time.Duration;
import java.time.format.DateTimeParseException;

public class DurationScalarConfiguration {

    public static GraphQLScalarType durationScalar() {
        return GraphQLScalarType.newScalar()
            .name("Duration")
            .description("Java Duration as scalar - e.g. \"PT1M\".")
            .coercing(new Coercing<Duration, String>() {
                @Override
                public String serialize(final Object dataFetcherResult) {
                    if (dataFetcherResult instanceof Duration) {
                        return ((Duration) dataFetcherResult).toString();
                    } else {
                        throw new CoercingSerializeException("Expected a Duration object.");
                    }
                }

                @Override
                public Duration parseValue(final Object input) {
                    try {
                        if (input instanceof String) {
                            return Duration.parse((String) input);
                        } else {
                            throw new CoercingParseValueException("Expected a String");
                        }
                    } catch (DateTimeParseException e) {
                        throw new CoercingParseValueException(String.format("Not a valid Duration: '%s'.", input), e
                        );
                    }
                }

                @Override
                public Duration parseLiteral(final Object input) {
                    if (input instanceof StringValue) {
                        try {
                            return Duration.parse(((StringValue) input).getValue());
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseLiteralException(e);
                        }
                    } else {
                        throw new CoercingParseLiteralException("Expected a StringValue.");
                    }
                }
            }).build();
    }
}