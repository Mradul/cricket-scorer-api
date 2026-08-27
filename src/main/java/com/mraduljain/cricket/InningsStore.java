package com.mraduljain.cricket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@ApplicationScoped
public class InningsStore {
    @Inject AgroalDataSource dataSource;
    @Inject ObjectMapper mapper;

    @PostConstruct void initialize() {
        try (var connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS innings_state (id VARCHAR(128) PRIMARY KEY, payload CLOB NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS innings_history (innings_id VARCHAR(128) NOT NULL, seq INT NOT NULL, payload CLOB NOT NULL, PRIMARY KEY(innings_id, seq))");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void pushHistory(String inningsId, InningsResource.InningsState beforeState) {
        try (var connection = dataSource.getConnection();
             PreparedStatement maxStmt = connection.prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM innings_history WHERE innings_id = ?");
             PreparedStatement insStmt = connection.prepareStatement("INSERT INTO innings_history (innings_id, seq, payload) VALUES (?, ?, ?)")) {
            maxStmt.setString(1, inningsId);
            int nextSeq = 1;
            try (ResultSet rs = maxStmt.executeQuery()) {
                if (rs.next()) nextSeq = rs.getInt(1) + 1;
            }
            insStmt.setString(1, inningsId);
            insStmt.setInt(2, nextSeq);
            insStmt.setString(3, mapper.writeValueAsString(beforeState));
            insStmt.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public InningsResource.InningsState popHistory(String inningsId) {
        try (var connection = dataSource.getConnection();
             PreparedStatement selStmt = connection.prepareStatement("SELECT seq, payload FROM innings_history WHERE innings_id = ? ORDER BY seq DESC LIMIT 1");
             PreparedStatement delStmt = connection.prepareStatement("DELETE FROM innings_history WHERE innings_id = ? AND seq = ?")) {
            selStmt.setString(1, inningsId);
            String payload = null;
            int seq = -1;
            try (ResultSet rs = selStmt.executeQuery()) {
                if (rs.next()) {
                    seq = rs.getInt(1);
                    payload = rs.getString(2);
                } else {
                    return null;
                }
            }
            delStmt.setString(1, inningsId);
            delStmt.setInt(2, seq);
            delStmt.executeUpdate();
            return mapper.readValue(payload, InningsResource.InningsState.class);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public InningsResource.InningsState find(String id) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT payload FROM innings_state WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapper.readValue(result.getString(1), InningsResource.InningsState.class) : null;
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void save(InningsResource.InningsState state) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "MERGE INTO innings_state (id, payload) KEY(id) VALUES (?, ?)")) {
            statement.setString(1, state.id);
            statement.setString(2, mapper.writeValueAsString(state));
            statement.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
