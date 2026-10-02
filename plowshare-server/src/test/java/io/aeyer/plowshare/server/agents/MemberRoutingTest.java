package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.*;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.session.*;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberRoutingTest {
    private final SessionRegistry sessions = new SessionRegistry();
    private final PresenceRegistry presences = new PresenceRegistry();
    private final ProjectMembers members = mock(ProjectMembers.class);
    private final RunProviders providers = new AgentsConfig().runProviders(mock(ProjectStore.class),
            mock(SessionChannel.class), sessions, presences, ImageStore.NONE, UnionRouting.NONE, members);

    private List<String> names(Home home, String owner) {
        return providers.forRun(home, List.of(), "desk", owner).stream().map(FileProvider::name).toList();
    }

    @Test void global_machine_requires_its_bound_owner() {
        sessions.attach("desk", Role.FILE_PROVIDER, new Object(), "alice");
        assertTrue(names(Home.global(), "alice").contains("remote"));
        assertFalse(names(Home.global(), "bob").contains("remote"));
        assertFalse(names(Home.global(), null).contains("remote"));
    }

    @Test void project_machine_requires_membership_and_unattended_runs_still_route() {
        sessions.attach("desk", Role.FILE_PROVIDER, new Object(), "alice");
        presences.declare(new Presence("desk", "laptop", "/repo", "ledger"));
        when(members.isMember("ledger", "alice")).thenReturn(true);
        assertTrue(names(Home.of("ledger"), "alice").contains("remote"));
        assertFalse(names(Home.of("ledger"), "bob").contains("remote"));
        assertTrue(names(Home.of("ledger"), null).contains("remote"));
        when(members.isMember("ledger", "alice")).thenThrow(new ArchiveUnavailableException("down", new IllegalStateException()));
        assertFalse(names(Home.of("ledger"), "alice").contains("remote"));
    }

    @Test void front_doors_refuse_another_accounts_session_but_allow_unknown_ids() {
        CallerAccess access = new CallerAccess(sessions, members);
        sessions.claim("desk", "alice");
        access.requireSession("desk", "alice");
        access.requireSession("unknown", "bob");
        access.requireSession(null, "bob");
        assertThrows(CallerFault.class, () -> access.requireSession("desk", "bob"));
        assertThrows(CallerFault.class, () -> access.requireSession("desk", null));
        when(members.mayUse("ledger", "alice")).thenReturn(true);
        access.requireProject("ledger", "alice");
        assertThrows(CallerFault.class, () -> access.requireProject("ledger", "bob"));
        assertThrows(CallerFault.class, () -> access.requireProject("ledger", null));
    }
}
