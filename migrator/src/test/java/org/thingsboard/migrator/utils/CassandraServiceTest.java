// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.cassandra.CassandraConnectionFailureException;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.CassandraQuerySyntaxException;
import org.springframework.data.cassandra.CassandraUncategorizedException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.cassandra.core.cql.PreparedStatementBinder;
import org.springframework.data.cassandra.core.cql.PreparedStatementCreator;
import org.springframework.data.cassandra.core.cql.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CassandraServiceTest {

    private CqlOperations cqlOperations;
    private CassandraService service;

    @BeforeEach
    void setUp() {
        cqlOperations = mock(CqlOperations.class);
        CassandraTemplate template = mock(CassandraTemplate.class);
        when(template.getCqlOperations()).thenReturn(cqlOperations);
        service = new CassandraService(template);
        ReflectionTestUtils.setField(service, "maxRetries", 3);
        ReflectionTestUtils.setField(service, "initialDelay", 1L);
        ReflectionTestUtils.setField(service, "multiplier", 1.0);
    }

    @Test
    void transientErrorsAreRetried() {
        when(query()).thenThrow(writeTimeout(), connectionFailure()).thenReturn(null);

        service.execute("INSERT", 1);

        verify(cqlOperations, times(3)).query(any(PreparedStatementCreator.class), any(PreparedStatementBinder.class), any(ResultSetExtractor.class));
        assertThat(activeErrors()).isZero();
    }

    @Test
    void nonRetryableErrorFailsImmediately() {
        when(query()).thenThrow(new CassandraInvalidQueryException("Invalid query"));

        assertThatThrownBy(() -> service.execute("INSERT", 1)).isInstanceOf(CassandraInvalidQueryException.class);

        verify(cqlOperations, times(1)).query(any(PreparedStatementCreator.class), any(PreparedStatementBinder.class), any(ResultSetExtractor.class));
        assertThat(activeErrors()).isZero();
    }

    @Test
    void nonRetryableErrorAfterTransientOneFailsImmediately() {
        when(query()).thenThrow(writeTimeout(), new CassandraInvalidQueryException("Invalid query"));

        assertThatThrownBy(() -> service.execute("INSERT", 1)).isInstanceOf(CassandraInvalidQueryException.class);

        verify(cqlOperations, times(2)).query(any(PreparedStatementCreator.class), any(PreparedStatementBinder.class), any(ResultSetExtractor.class));
        assertThat(activeErrors()).isZero();
    }

    @Test
    void givesUpAfterMaxRetries() {
        when(query()).thenThrow(writeTimeout());

        assertThatThrownBy(() -> service.execute("INSERT", 1)).hasMessageContaining("after 3 retries");

        verify(cqlOperations, times(4)).query(any(PreparedStatementCreator.class), any(PreparedStatementBinder.class), any(ResultSetExtractor.class));
        assertThat(activeErrors()).isZero();
    }

    @Test
    void retryableErrors() {
        assertThat(CassandraService.isRetryable(writeTimeout())).isTrue();
        assertThat(CassandraService.isRetryable(connectionFailure())).isTrue();
        assertThat(CassandraService.isRetryable(new TransientDataAccessResourceException("Overloaded"))).isTrue();
        assertThat(CassandraService.isRetryable(new CassandraUncategorizedException("Closed", new ClosedConnectionException("Closed")))).isTrue();

        assertThat(CassandraService.isRetryable(new CassandraInvalidQueryException("Invalid query"))).isFalse();
        assertThat(CassandraService.isRetryable(new CassandraQuerySyntaxException("Syntax error"))).isFalse();
        assertThat(CassandraService.isRetryable(new CassandraUncategorizedException("Codec not found", new IllegalArgumentException()))).isFalse();
        assertThat(CassandraService.isRetryable(new ClassCastException())).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Object query() {
        return cqlOperations.query(any(PreparedStatementCreator.class), any(PreparedStatementBinder.class), any(ResultSetExtractor.class));
    }

    private int activeErrors() {
        return ((AtomicInteger) ReflectionTestUtils.getField(service, "activeErrors")).get();
    }

    private static CassandraWriteTimeoutException writeTimeout() {
        return new CassandraWriteTimeoutException("SIMPLE", "Write timeout", null);
    }

    private static CassandraConnectionFailureException connectionFailure() {
        return new CassandraConnectionFailureException(Map.of(), "No node available", null);
    }

}
