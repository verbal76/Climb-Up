package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Owner capability-verification directive (2026-10-27, section 22): new movement types must not appear quietly. docs/verification/coverage.json is
 * the machine-readable register of every player mechanic, mode transition and element type with its game, solver and proof status. This gate fails
 * when the game gains an element type or a player mode the register does not list, when an entry cites a test that does not exist, or when a status is
 * not one of the register's five. It does not judge the statuses themselves (docs/verification/SOLVER_COVERAGE_REGISTER.md does, with evidence).
 */
public class VerificationCoverageGateTest {
    private static JsonValue cov() throws Exception { return new JsonReader().parse(new String(Files.readAllBytes(Paths.get("../docs/verification/coverage.json")), "UTF-8")); }
    private static Set<String> strings(JsonValue a) { Set<String> s = new LinkedHashSet<>(); for (JsonValue v = a.child; v != null; v = v.next) s.add(v.asString()); return s; }

    @Test public void everyElementTypeOfTheGameIsInTheRegister() throws Exception {
        Set<String> listed = strings(cov().get("element_types"));
        for (Element.Type t : Element.Type.values()) assertTrue("Element.Type." + t + " is not in docs/verification/coverage.json: review its entry/exit and solver coverage before adding it", listed.contains(t.name()));
        for (String s : listed) Element.Type.valueOf(s);          // and nothing listed that the game no longer has
    }

    @Test public void everyPlayerModeIsInTheRegisterAndCoveredByTransitions() throws Exception {
        JsonValue c = cov();
        Set<String> modes = strings(c.get("modes")), seen = new HashSet<>();
        for (Sim.Mode m : Sim.Mode.values()) assertTrue("Sim.Mode." + m + " is not in the register", modes.contains(m.name()));
        for (JsonValue t = c.get("transitions").child; t != null; t = t.next) { seen.add(t.getString("frm").replaceAll("\\(.*", "")); seen.add(t.getString("to").replaceAll("\\(.*", "")); }
        for (Sim.Mode m : Sim.Mode.values()) assertTrue("no transition into or out of " + m, seen.contains(m.name()));
    }

    @Test public void entriesAreWellFormedAndCiteRealTests() throws Exception {
        JsonValue c = cov();
        Set<String> statuses = strings(c.get("statuses")), ids = new HashSet<>();
        for (JsonValue m = c.get("mechanics").child; m != null; m = m.next) {
            String id = m.getString("id");
            assertTrue("duplicate mechanic " + id, ids.add(id));
            for (String f : new String[]{"sim_status", "solver_status", "proof_status"}) assertTrue(id + " " + f + ": " + m.getString(f), statuses.contains(m.getString(f)));
            for (String ref : strings(m.get("sim_tests"))) assertTrue(id + " cites a missing test: " + ref, testExists(ref));
        }
        for (JsonValue t = c.get("transitions").child; t != null; t = t.next) assertTrue(t.getString("id") + " names an unknown mechanic", ids.contains(t.getString("mechanic")));
    }

    /** "Class" or "Class#method", looked up in this test tree's packages. */
    static boolean testExists(String ref) {
        String cls = ref.contains("#") ? ref.substring(0, ref.indexOf('#')) : ref, method = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : null;
        for (String pkg : new String[]{"com.hotatticgames.climbup.", "com.hotatticgames.climbup.sim."}) {
            try {
                Class<?> k = Class.forName(pkg + cls);
                if (method == null) return true;
                for (Method mm : k.getMethods()) if (mm.getName().equals(method) && mm.isAnnotationPresent(Test.class)) return true;
                return false;
            } catch (ClassNotFoundException e) { /* next package */ }
        }
        return false;
    }
}
