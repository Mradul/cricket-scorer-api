package com.mraduljain.cricket;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Path("/matches")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MatchResource {
    @Inject MatchStore store;

    @POST
    public Response create(CreateMatch request) {
        if (request == null || request.teamA == null || request.teamB == null) bad("two teams are required");
        requireText(request.teamA.id, "teamA.id");
        requireText(request.teamB.id, "teamB.id");
        if (request.teamA.id.equals(request.teamB.id)) bad("team ids must differ");
        String id = blank(request.id) ? UUID.randomUUID().toString() : request.id;
        if (store.find(id) != null) conflict("match already exists");
        MatchState state = new MatchState();
        state.id = id;
        state.status = MatchStatus.SETUP;
        state.teamA = new TeamState(request.teamA.id, request.teamA.name);
        state.teamB = new TeamState(request.teamB.id, request.teamB.name);
        store.save(state);
        return Response.created(URI.create("/matches/" + id)).entity(state).build();
    }

    @PUT
    @Path("/{id}/squads/{teamId}")
    public MatchState registerSquad(@PathParam("id") String id, @PathParam("teamId") String teamId, SquadRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        TeamState team = requireTeam(state, teamId);
        if (request == null || request.players == null || request.players.isEmpty()) bad("players are required");
        LinkedHashMap<String, Player> players = new LinkedHashMap<>();
        for (Player player : request.players) {
            if (player == null) bad("player is required");
            requireText(player.id, "player.id");
            if (players.putIfAbsent(player.id, player) != null) bad("duplicate player");
        }
        team.squad = players;
        state.playingXis.remove(teamId);
        state.roles.remove(teamId);
        store.save(state);
        return state;
    }

    @PUT
    @Path("/{id}/playing-xi/{teamId}")
    public MatchState selectXi(@PathParam("id") String id, @PathParam("teamId") String teamId, PlayingXiRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        TeamState team = requireTeam(state, teamId);
        if (request == null || request.playerIds == null || request.playerIds.size() != 11) {
            bad("playing XI must contain exactly 11 players");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(request.playerIds);
        if (ids.size() != 11) bad("playing XI contains duplicate players");
        if (!team.squad.keySet().containsAll(ids)) bad("playing XI must be a subset of the registered squad");
        state.playingXis.put(teamId, new ArrayList<>(ids));
        state.roles.remove(teamId);
        store.save(state);
        return state;
    }

    @PUT
    @Path("/{id}/roles/{teamId}")
    public MatchState assignRoles(@PathParam("id") String id, @PathParam("teamId") String teamId, RolesRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        requireTeam(state, teamId);
        List<String> xi = state.playingXis.get(teamId);
        if (xi == null) conflict("playing XI must be selected first");
        if (request == null || blank(request.captainId) || blank(request.wicketkeeperId)) {
            bad("one captain and one wicketkeeper are required");
        }
        if (!xi.contains(request.captainId) || !xi.contains(request.wicketkeeperId)) {
            bad("captain and wicketkeeper must be in the playing XI");
        }
        state.roles.put(teamId, new RoleAssignment(request.captainId, request.wicketkeeperId));
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/toss")
    public MatchState recordToss(@PathParam("id") String id, TossRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        if (state.toss != null) conflict("toss has already been recorded");
        if (request == null || blank(request.decision)) bad("toss decision is required");
        String decisionStr = request.decision.trim().toUpperCase();
        TossDecision decision;
        try {
            decision = TossDecision.valueOf(decisionStr);
        } catch (IllegalArgumentException e) {
            bad("toss decision must be BAT or BOWL");
            return null; // unreachable
        }
        requireTeam(state, request.winnerTeamId);
        // Store original casing decision as enum for deriveSides
        state.toss = new Toss(request.winnerTeamId, decision);
        deriveSides(state);
        store.save(state);
        return state;
    }

    @PUT
    @Path("/{id}/rules")
    public MatchState setRules(@PathParam("id") String id, MatchRulesRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        if (request == null) bad("rules are required");
        int totalOvers = requireInteger(request.totalOvers, "totalOvers");
        int powerplayOvers = requireInteger(request.powerplayOvers, "powerplayOvers");
        int wideRuns = requireInteger(request.wideRuns, "wideRuns");
        int noBallRuns = requireInteger(request.noBallRuns, "noBallRuns");
        int maxOversPerBowler = requireInteger(request.maxOversPerBowler, "maxOversPerBowler");
        if (totalOvers <= 0) bad("totalOvers must be positive");
        if (powerplayOvers < 0 || powerplayOvers > totalOvers) {
            bad("powerplayOvers must be between zero and totalOvers");
        }
        if (wideRuns < 1 || noBallRuns < 1) bad("penalty values must be at least one");
        if (maxOversPerBowler < 1 || maxOversPerBowler > totalOvers) {
            bad("maxOversPerBowler is invalid");
        }
        MatchRules rules = new MatchRules();
        rules.totalOvers = totalOvers;
        rules.powerplayOvers = powerplayOvers;
        rules.wideRuns = wideRuns;
        rules.noBallRuns = noBallRuns;
        rules.maxOversPerBowler = maxOversPerBowler;
        state.rules = rules;
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/innings/start")
    public MatchState start(@PathParam("id") String id, StartRequest request) {
        MatchState state = requireMatch(id);
        requireSetup(state);
        if (!setupComplete(state)) conflict("match setup is incomplete");
        if (request == null || blank(request.strikerId) || blank(request.nonStrikerId) || blank(request.openingBowlerId)) {
            bad("striker, non-striker, and opening bowler are required");
        }
        if (request.strikerId.equals(request.nonStrikerId)) bad("striker and non-striker must differ");
        List<String> battingXi = state.playingXis.get(state.battingTeamId);
        List<String> bowlingXi = state.playingXis.get(state.bowlingTeamId);
        if (!battingXi.contains(request.strikerId) || !battingXi.contains(request.nonStrikerId)) {
            bad("both batters must be in the batting XI");
        }
        if (!bowlingXi.contains(request.openingBowlerId)) bad("opening bowler must be in the bowling XI");
        state.strikerId = request.strikerId;
        state.nonStrikerId = request.nonStrikerId;
        state.openingBowlerId = request.openingBowlerId;
        state.status = MatchStatus.LIVE;
        store.save(state);
        return state;
    }

    @GET
    @Path("/{id}")
    public MatchState read(@PathParam("id") String id) {
        return requireMatch(id);
    }

    private boolean setupComplete(MatchState state) {
        if (state.toss == null || state.rules == null || state.battingTeamId == null || state.bowlingTeamId == null) return false;
        for (String teamId : List.of(state.teamA.id, state.teamB.id)) {
            List<String> xi = state.playingXis.get(teamId);
            RoleAssignment roles = state.roles.get(teamId);
            TeamState team = requireTeam(state, teamId);
            if (xi == null || xi.size() != 11 || new LinkedHashSet<>(xi).size() != 11) return false;
            if (!team.squad.keySet().containsAll(xi)) return false;
            if (roles == null || !xi.contains(roles.captainId) || !xi.contains(roles.wicketkeeperId)) return false;
        }
        return true;
    }

    private void deriveSides(MatchState state) {
        String other = state.toss.winnerTeamId.equals(state.teamA.id) ? state.teamB.id : state.teamA.id;
        state.battingTeamId = state.toss.decision == TossDecision.BAT ? state.toss.winnerTeamId : other;
        state.bowlingTeamId = state.battingTeamId.equals(state.teamA.id) ? state.teamB.id : state.teamA.id;
    }

    private MatchState requireMatch(String id) {
        MatchState state = store.find(id);
        if (state == null) throw new ApiException(404, "match not found");
        return state;
    }

    private TeamState requireTeam(MatchState state, String teamId) {
        if (Objects.equals(state.teamA.id, teamId)) return state.teamA;
        if (Objects.equals(state.teamB.id, teamId)) return state.teamB;
        throw new ApiException(400, "team is not part of the match");
    }

    private void requireSetup(MatchState state) {
        if (state.status != MatchStatus.SETUP) conflict("match is not in SETUP");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireText(String value, String field) { if (blank(value)) bad(field + " is required"); }
    private static int requireInteger(Object value, String field) {
        if (!(value instanceof Number)) bad(field + " must be an integer");
        Number number = (Number) value;
        double raw = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(raw) || raw != integer) bad(field + " must be an integer");
        return integer;
    }
    private static void bad(String message) { throw new ApiException(400, message); }
    private static void conflict(String message) { throw new ApiException(409, message); }

    public enum MatchStatus { SETUP, LIVE, COMPLETE }
    public enum TossDecision { BAT, BOWL }
    public enum Role { CAPTAIN, WICKETKEEPER }

    public static class Player {
        public String id;
        public String name;
        public Player() {}
        public Player(String id, String name) { this.id = id; this.name = name; }
    }

    public static class TeamInput { public String id; public String name; }
    public static class CreateMatch { public String id; public TeamInput teamA; public TeamInput teamB; }
    public static class SquadRequest { public List<Player> players; }
    public static class PlayingXiRequest { public List<String> playerIds; }
    public static class RolesRequest { public String captainId; public String wicketkeeperId; }
    public static class TossRequest { public String winnerTeamId; public String decision; }
    public static class StartRequest { public String strikerId; public String nonStrikerId; public String openingBowlerId; }
    public static class MatchRulesRequest {
        public Object totalOvers;
        public Object powerplayOvers;
        public Object wideRuns;
        public Object noBallRuns;
        public Object maxOversPerBowler;
    }

    public static class TeamState {
        public String id;
        public String name;
        public Map<String, Player> squad = new LinkedHashMap<>();
        public TeamState() {}
        public TeamState(String id, String name) { this.id = id; this.name = name; }
    }

    public static class RoleAssignment {
        public String captainId;
        public String wicketkeeperId;
        public RoleAssignment() {}
        public RoleAssignment(String captainId, String wicketkeeperId) {
            this.captainId = captainId;
            this.wicketkeeperId = wicketkeeperId;
        }
    }

    public static class Toss {
        public String winnerTeamId;
        public TossDecision decision;
        public Toss() {}
        public Toss(String winnerTeamId, TossDecision decision) { this.winnerTeamId = winnerTeamId; this.decision = decision; }
    }

    public static class MatchRules {
        public int totalOvers;
        public int powerplayOvers;
        public int wideRuns;
        public int noBallRuns;
        public int maxOversPerBowler;
    }

    public static class MatchState {
        public String id;
        public MatchStatus status;
        public TeamState teamA;
        public TeamState teamB;
        public Map<String, List<String>> playingXis = new LinkedHashMap<>();
        public Map<String, RoleAssignment> roles = new LinkedHashMap<>();
        public Toss toss;
        public MatchRules rules;
        public String battingTeamId;
        public String bowlingTeamId;
        public String strikerId;
        public String nonStrikerId;
        public String openingBowlerId;
    }
}
