package com.codimango.cricket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@ApplicationScoped
public class MatchStore {
    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper mapper;

    @PostConstruct
    void initialize() {
        try (var connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS match_state (id VARCHAR(128) PRIMARY KEY, payload CLOB NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public MatchResource.MatchState find(String id) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT payload FROM match_state WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapper.readValue(result.getString(1), MatchResource.MatchState.class) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void save(MatchResource.MatchState state) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "MERGE INTO match_state (id, payload) KEY(id) VALUES (?, ?)")) {
            statement.setString(1, state.id);
            statement.setString(2, mapper.writeValueAsString(state));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
