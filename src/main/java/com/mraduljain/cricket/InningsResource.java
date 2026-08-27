package com.mraduljain.cricket;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/innings")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InningsResource {
    @Inject InningsStore store;
    @Inject ObjectMapper mapper;

    @POST
    public Response initialize(InitRequest request) {
        if (request == null) bad("initialization payload is required");
        String id = blank(request.id) ? UUID.randomUUID().toString() : request.id;
        if (store.find(id) != null) conflict("innings already exists");
        validateLineup(request.battingLineup, 11, "battingLineup");
        validateLineup(request.bowlingLineup, 1, "bowlingLineup");
        if (request.strikerId == null || request.strikerId.equals(request.nonStrikerId)) bad("opening batters must differ");
        if (!request.battingLineup.contains(request.strikerId) || !request.battingLineup.contains(request.nonStrikerId)) {
            bad("opening batters must be in the batting lineup");
        }
        if (!request.bowlingLineup.contains(request.openingBowlerId)) bad("opening bowler must be in the bowling lineup");
        if (request.totalOvers <= 0 || request.maxOversPerBowler <= 0 || request.maxOversPerBowler > request.totalOvers) {
            bad("over rules are invalid");
        }
        if (request.wideRuns < 1 || request.noBallRuns < 1) bad("wide and no-ball penalties must be positive");
        if (request.target != null && request.target <= 0) bad("target must be positive");

        InningsState state = new InningsState();
        state.id = id;
        state.status = InningsStatus.LIVE;
        state.battingLineup = new ArrayList<>(request.battingLineup);
        state.bowlingLineup = new ArrayList<>(request.bowlingLineup);
        state.strikerId = request.strikerId;
        state.nonStrikerId = request.nonStrikerId;
        state.currentBowlerId = request.openingBowlerId;
        state.totalOvers = request.totalOvers;
        state.maxOversPerBowler = request.maxOversPerBowler;
        state.wideRuns = request.wideRuns;
        state.noBallRuns = request.noBallRuns;
        state.target = request.target;
        state.currentOverNumber = 1;
        for (String idValue : state.battingLineup) state.batterCards.put(idValue, new BatterCard(idValue));
        for (String idValue : state.bowlingLineup) state.bowlerCards.put(idValue, new BowlerCard(idValue));
        for (ExtraType type : ExtraType.values()) state.extras.put(type, 0);
        store.save(state);
        return Response.created(URI.create("/innings/" + id)).entity(state).build();
    }

    @POST
    @Path("/{id}/deliveries")
    public synchronized InningsState delivery(
            @PathParam("id") String id,
            @HeaderParam("If-Match-Version") String expectedVersion,
            DeliveryRequest request) {
        InningsState state = requireLive(id);
        requireVersion(state, expectedVersion);
        requireBowler(state);
        InningsState before = copy(state);
        ScoredBall ball = score(state, request);
        if ((ball.runsAdded & 1) == 1) swapStrike(state);
        if (ball.overEnded) swapStrike(state);
        finishBall(state, ball.overEnded);
        state.version++;
        store.pushHistory(id, before);
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/wickets")
    public synchronized InningsState wicket(
            @PathParam("id") String id,
            @HeaderParam("If-Match-Version") String expectedVersion,
            WicketRequest request) {
        InningsState state = requireLive(id);
        requireVersion(state, expectedVersion);
        requireBowler(state);
        if (request == null || blank(request.dismissalType) || blank(request.batterOutId)) bad("dismissal details are required");
        DismissalType dismissal;
        try {
            dismissal = DismissalType.valueOf(request.dismissalType.trim().toUpperCase());
        } catch (Exception e) {
            bad("dismissal type must be one of BOWLED, CAUGHT, LBW, RUN_OUT, STUMPED, HIT_WICKET");
            return null;
        }
        if (!request.batterOutId.equals(state.strikerId) && !request.batterOutId.equals(state.nonStrikerId)) {
            bad("dismissed batter must be at the crease");
        }
        if (dismissal != DismissalType.RUN_OUT && !request.batterOutId.equals(state.strikerId)) {
            bad("only a run out may dismiss the non-striker");
        }
        // extra type parsing for NO_BALL check - need to know if extra is NO_BALL before scoring
        ExtraType extraTypeForCheck = null;
        if (request.extra != null && !blank(request.extra.type)) {
            try {
                extraTypeForCheck = ExtraType.valueOf(request.extra.type.trim().toUpperCase());
            } catch (Exception ex) {
                bad("extra type must be one of WIDE, NO_BALL, BYE, LEG_BYE, PENALTY");
            }
        }
        if (extraTypeForCheck == ExtraType.NO_BALL && dismissal != DismissalType.RUN_OUT) {
            bad("only run out is legal dismissal on a no-ball");
        }
        int wicketsAfter = state.wickets + 1;
        boolean allOutAfter = wicketsAfter >= state.battingLineup.size() - 1;
        if (!allOutAfter) validateIncoming(state, request.incomingBatterId);
        else if (!blank(request.incomingBatterId)) validateIncoming(state, request.incomingBatterId);

        InningsState beforeWicket = copy(state);
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.runsOffBat = request.runsOffBat;
        delivery.extra = request.extra;
        ScoredBall ball = score(state, delivery);

        BatterCard dismissed = state.batterCards.get(request.batterOutId);
        dismissed.out = true;
        dismissed.dismissalType = dismissal;
        state.wickets = wicketsAfter;
        if (dismissal != DismissalType.RUN_OUT) {
            state.bowlerCards.get(state.currentBowlerId).wickets++;
        }
        FallOfWicket fall = new FallOfWicket();
        fall.wicketNumber = state.wickets;
        fall.batterId = request.batterOutId;
        fall.teamScore = state.totalRuns;
        fall.dismissalType = dismissal;
        state.fallOfWickets.add(fall);
        Delivery recorded = state.deliveries.get(state.deliveries.size() - 1);
        recorded.dismissalType = dismissal;
        recorded.batterOutId = request.batterOutId;

        if (!allOutAfter) {
            String survivor = request.batterOutId.equals(state.strikerId) ? state.nonStrikerId : state.strikerId;
            boolean survivorFaces = dismissal == DismissalType.RUN_OUT && request.crossed;
            state.strikerId = survivorFaces ? survivor : request.incomingBatterId;
            state.nonStrikerId = survivorFaces ? request.incomingBatterId : survivor;
            if (ball.overEnded) swapStrike(state);
        }
        finishBall(state, ball.overEnded);
        state.version++;
        store.pushHistory(id, beforeWicket);
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/bowler")
    public synchronized InningsState assignBowler(
            @PathParam("id") String id,
            @HeaderParam("If-Match-Version") String expectedVersion,
            BowlerRequest request) {
        InningsState state = requireLive(id);
        requireVersion(state, expectedVersion);
        if (!state.awaitingBowler) conflict("a new bowler is not currently required");
        if (request == null || !state.bowlingLineup.contains(request.bowlerId)) bad("bowler must be in the bowling lineup");
        if (request.bowlerId.equals(state.lastOverBowlerId)) bad("the same bowler cannot bowl consecutive overs");
        BowlerCard card = state.bowlerCards.get(request.bowlerId);
        if (card.legalBalls >= state.maxOversPerBowler * 6) bad("bowler has reached the over limit");
        state.currentBowlerId = request.bowlerId;
        state.awaitingBowler = false;
        state.version++;
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/close")
    public synchronized InningsState close(
            @PathParam("id") String id,
            @HeaderParam("If-Match-Version") String expectedVersion) {
        InningsState state = requireLive(id);
        requireVersion(state, expectedVersion);
        complete(state, "DECLARED");
        state.version++;
        store.save(state);
        return state;
    }

    @POST
    @Path("/{id}/undo")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public synchronized InningsState undo(
            @PathParam("id") String id,
            @HeaderParam("If-Match-Version") String expectedVersion) {
        InningsState state = store.find(id);
        if (state == null) throw new ApiException(404, "innings not found");
        requireVersion(state, expectedVersion);
        if (state.deliveries.isEmpty()) throw new ApiException(409, "no deliveries to undo");
        InningsState before = store.popHistory(id);
        if (before == null) throw new ApiException(409, "no deliveries to undo");
        // before is the snapshot immediately before the last delivery
        // Preserve id, increment version relative to current
        before.version = state.version + 1;
        before.id = state.id;
        store.save(before);
        return before;
    }

    @GET
    @Path("/{id}")
    public InningsState read(@PathParam("id") String id) { return requireState(id); }

    private ScoredBall score(InningsState state, DeliveryRequest request) {
        if (request == null || request.runsOffBat < 0) bad("runsOffBat must be non-negative");
        ExtraType type = null;
        int extraRuns = 0;
        if (request.extra != null) {
            extraRuns = request.extra.runs;
            String typeStr = request.extra.type;
            if (blank(typeStr) || extraRuns <= 0) bad("extra type and positive runs are required");
            try {
                type = ExtraType.valueOf(typeStr.trim().toUpperCase());
            } catch (Exception e) {
                bad("extra type must be one of WIDE, NO_BALL, BYE, LEG_BYE, PENALTY");
            }
        }
        if (type == ExtraType.WIDE && extraRuns < state.wideRuns) bad("wide runs are below the configured penalty");
        if (type == ExtraType.NO_BALL && extraRuns < state.noBallRuns) bad("no-ball runs are below the configured penalty");
        if ((type == ExtraType.BYE || type == ExtraType.LEG_BYE || type == ExtraType.WIDE) && request.runsOffBat != 0) {
            bad("bat runs cannot accompany this extra type");
        }
        boolean legal = type != ExtraType.WIDE && type != ExtraType.NO_BALL;
        int runsAdded = request.runsOffBat + extraRuns;
        BatterCard batter = state.batterCards.get(state.strikerId);
        BowlerCard bowler = state.bowlerCards.get(state.currentBowlerId);
        batter.runs += request.runsOffBat;
        if (legal) batter.balls++;
        int conceded = request.runsOffBat;
        if (type == ExtraType.WIDE || type == ExtraType.NO_BALL) conceded += extraRuns;
        bowler.runsConceded += conceded;
        if (type != null) state.extras.put(type, state.extras.get(type) + extraRuns);
        state.totalRuns += runsAdded;
        if (legal) {
            state.legalBalls++;
            state.currentOverLegalBalls++;
            bowler.legalBalls++;
        }
        Delivery recorded = new Delivery();
        recorded.sequence = state.deliveries.size() + 1;
        recorded.strikerId = state.strikerId;
        recorded.bowlerId = state.currentBowlerId;
        recorded.runsOffBat = request.runsOffBat;
        recorded.extraType = type;
        recorded.extraRuns = extraRuns;
        recorded.legal = legal;
        state.deliveries.add(recorded);
        return new ScoredBall(runsAdded, legal && state.currentOverLegalBalls == 6);
    }

    private void finishBall(InningsState state, boolean overEnded) {
        if (state.target != null && state.totalRuns >= state.target) complete(state, "TARGET_REACHED");
        else if (state.wickets >= state.battingLineup.size() - 1) complete(state, "ALL_OUT");
        else if (state.legalBalls >= state.totalOvers * 6) complete(state, "OVERS_EXHAUSTED");
        if (state.status == InningsStatus.LIVE && overEnded) {
            state.lastOverBowlerId = state.currentBowlerId;
            state.currentBowlerId = null;
            state.currentOverLegalBalls = 0;
            state.currentOverNumber++;
            state.awaitingBowler = true;
        }
    }

    private void validateIncoming(InningsState state, String incoming) {
        if (blank(incoming) || !state.battingLineup.contains(incoming)) bad("incoming batter is not available or must be in lineup");
        BatterCard card = state.batterCards.get(incoming);
        if (card.out || incoming.equals(state.strikerId) || incoming.equals(state.nonStrikerId)) {
            bad("incoming batter is not available or must be in lineup");
        }
    }

    private void validateLineup(List<String> lineup, int minimum, String field) {
        if (lineup == null || lineup.size() < minimum || new LinkedHashSet<>(lineup).size() != lineup.size()) {
            bad(field + " is invalid");
        }
        if (field.equals("battingLineup") && lineup.size() != 11) bad("battingLineup must contain exactly 11 players");
        if (lineup.stream().anyMatch(InningsResource::blank)) bad(field + " contains a blank id");
    }

    private void requireBowler(InningsState state) {
        if (state.awaitingBowler || blank(state.currentBowlerId)) conflict("a new bowler must be assigned");
    }

    private void requireVersion(InningsState state, String rawExpectedVersion) {
        if (rawExpectedVersion == null) return;
        int expectedVersion;
        try {
            expectedVersion = Integer.parseInt(rawExpectedVersion);
        } catch (NumberFormatException exception) {
            bad("If-Match-Version must be a non-negative integer");
            return;
        }
        if (expectedVersion < 0) bad("If-Match-Version must be a non-negative integer");
        if (expectedVersion != state.version) conflict("innings version does not match");
    }

    private InningsState requireLive(String id) {
        InningsState state = requireState(id);
        if (state.status != InningsStatus.LIVE) conflict("innings is complete");
        return state;
    }

    private InningsState requireState(String id) {
        InningsState state = store.find(id);
        if (state == null) throw new ApiException(404, "innings not found");
        return state;
    }

    private void complete(InningsState state, String reason) { state.status = InningsStatus.COMPLETE; state.completionReason = reason; }
    private void swapStrike(InningsState state) { String value = state.strikerId; state.strikerId = state.nonStrikerId; state.nonStrikerId = value; }
    private InningsState copy(InningsState state) {
        try {
            return mapper.readValue(mapper.writeValueAsString(state), InningsState.class);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void bad(String message) { throw new ApiException(400, message); }
    private static void conflict(String message) { throw new ApiException(409, message); }

    private record ScoredBall(int runsAdded, boolean overEnded) {}

    public enum ExtraType { WIDE, NO_BALL, BYE, LEG_BYE, PENALTY }
    public enum DismissalType { BOWLED, CAUGHT, LBW, RUN_OUT, STUMPED, HIT_WICKET }
    public enum InningsStatus { LIVE, COMPLETE }

    public static class InitRequest {
        public String id;
        public List<String> battingLineup;
        public List<String> bowlingLineup;
        public String strikerId;
        public String nonStrikerId;
        public String openingBowlerId;
        public int totalOvers;
        public int maxOversPerBowler;
        public int wideRuns;
        public int noBallRuns;
        public Integer target;
    }
    public static class Extra { public String type; public int runs; }
    public static class DeliveryRequest { public int runsOffBat; public Extra extra; }
    public static class WicketRequest extends DeliveryRequest {
        public String dismissalType;
        public String batterOutId;
        public String incomingBatterId;
        public boolean crossed;
    }
    public static class BowlerRequest { public String bowlerId; }

    public static class BatterCard {
        public String batterId;
        public int runs;
        public int balls;
        public boolean out;
        public DismissalType dismissalType;
        public BatterCard() {}
        public BatterCard(String batterId) { this.batterId = batterId; }
    }
    public static class BowlerCard {
        public String bowlerId;
        public int runsConceded;
        public int legalBalls;
        public int wickets;
        public BowlerCard() {}
        public BowlerCard(String bowlerId) { this.bowlerId = bowlerId; }
    }
    public static class Delivery {
        public int sequence;
        public String strikerId;
        public String bowlerId;
        public int runsOffBat;
        public ExtraType extraType;
        public int extraRuns;
        public boolean legal;
        public DismissalType dismissalType;
        public String batterOutId;
    }
    public static class FallOfWicket {
        public int wicketNumber;
        public String batterId;
        public int teamScore;
        public DismissalType dismissalType;
    }
    public static class InningsState {
        public String id;
        public int version;
        public InningsStatus status;
        public String completionReason;
        public List<String> battingLineup = new ArrayList<>();
        public List<String> bowlingLineup = new ArrayList<>();
        public int totalOvers;
        public int maxOversPerBowler;
        public int wideRuns;
        public int noBallRuns;
        public Integer target;
        public int totalRuns;
        public int wickets;
        public int legalBalls;
        public int currentOverLegalBalls;
        public int currentOverNumber;
        public String strikerId;
        public String nonStrikerId;
        public String currentBowlerId;
        public String lastOverBowlerId;
        public boolean awaitingBowler;
        public Map<ExtraType, Integer> extras = new EnumMap<>(ExtraType.class);
        public Map<String, BatterCard> batterCards = new LinkedHashMap<>();
        public Map<String, BowlerCard> bowlerCards = new LinkedHashMap<>();
        public List<Delivery> deliveries = new ArrayList<>();
        public List<FallOfWicket> fallOfWickets = new ArrayList<>();
    }
}
