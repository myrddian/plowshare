package io.aeyer.plowshare.server.access;

import static org.mockito.Mockito.mock;

import java.util.List;
import org.mockito.Answers;

/**
 * A {@link ScopedAuthority} mock whose {@code walk} forwards every check to the mock's own
 * five-argument {@code allowsUsing}, so a test stubs one method and the walk-based callers see it.
 */
public final class ScopedAuthorityMocks {
  private ScopedAuthorityMocks() {}

  public static ScopedAuthority mockAuthority() {
    return mock(
        ScopedAuthority.class,
        invocation -> {
          if (!invocation.getMethod().getName().equals("walk"))
            return Answers.RETURNS_DEFAULTS.answer(invocation);
          ScopedAuthority self = (ScopedAuthority) invocation.getMock();
          GrantRecipient recipient = invocation.getArgument(0);
          List<ScopedGrantRepository.Binding> live = invocation.getArgument(1);
          return (ScopedAuthority.Walk)
              (target, action, grant) -> self.allowsUsing(recipient, target, action, grant, live);
        });
  }
}
