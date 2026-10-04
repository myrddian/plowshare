package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link AdminSeed} — the only class in this slice with no direct coverage before this file, and
 * the boot refusal is task 1's headline safety property: this is what stands between an operator
 * and a permanent default password if a mistake, not a review, is ever what catches one.
 *
 * <h2>Mocks for the boot logic, a real database for the one thing a mock cannot honestly answer
 * </h2>
 *
 * <p>Every method here but {@link
 * #a_real_password_creates_the_account_carrying_must_change_password()} runs {@link AdminSeed}
 * against mocked {@link AdminStore}, {@link PasswordHasher} and {@link Environment} collaborators —
 * {@code AdminSeed} is a plain object with no Spring context of its own to build, so a unit test is
 * the whole instrument each of those needs, on {@code PasswordHasherTest}'s own reasoning for
 * skipping a container. The one exception runs against a real Postgres, migrated through the whole
 * chain exactly as {@code AdminStoreTest} does, because "the row this class writes carries {@code
 * must_change_password}" is a claim about a column's actual default and not about which method this
 * class called — a mock {@link AdminStore#create} could return successfully having recorded nothing
 * at all, and would not be lying about anything this test could tell from the mock alone.
 */
@Tag("full-db")
@Testcontainers
class AdminSeedTest {

  /**
   * The pgvector image, {@code AdminStoreTest}'s own reason: {@code V1}'s first line is {@code
   * CREATE EXTENSION vector} and this class runs the whole migration chain for its one
   * real-database test.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private final AdminStore admins = mock(AdminStore.class);
  private final PasswordHasher hasher = mock(PasswordHasher.class);
  private final AuthProperties properties = new AuthProperties();
  private final Environment environment = mock(Environment.class);

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void freshAdmins() {
    // CASCADE, since V40 gives `schedules` and `triggers` a
    // `defined_by TEXT REFERENCES admins (handle)`: a plain TRUNCATE is
    // refused once a referencing table exists, even an empty one.
    jdbc.execute("TRUNCATE TABLE admins CASCADE");
  }

  private AdminSeed seed() {
    return new AdminSeed(admins, hasher, properties, environment);
  }

  @Test
  void real_server_first_run_announces_a_random_setup_account() {
    properties.setFirstRunSetup(true);
    when(hasher.hash(any(char[].class))).thenReturn("hash-of-temporary");
    when(admins.bootstrap("hash-of-temporary")).thenReturn(true);
    var output = new java.io.ByteArrayOutputStream();
    var original = System.err;
    try {
      System.setErr(new java.io.PrintStream(output));
      seed().run(null);
    } finally {
      System.setErr(original);
    }
    String announcement = output.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(announcement.contains("temporary account admin"));
    assertTrue(announcement.contains("plowshare-cli setup"));
    assertTrue(
        java.util.regex.Pattern.compile("Temporary password: [a-f0-9]{48}")
            .matcher(announcement)
            .find());
    assertTrue(!announcement.contains("hash-of-temporary"));
    verify(admins).bootstrap("hash-of-temporary");
  }

  @Test
  void completed_setup_never_announces_another_temporary_password() {
    properties.setFirstRunSetup(true);
    when(hasher.hash(any(char[].class))).thenReturn("hash-of-temporary");
    var output = new java.io.ByteArrayOutputStream();
    var original = System.err;
    try {
      System.setErr(new java.io.PrintStream(output));
      seed().run(null);
    } finally {
      System.setErr(original);
    }
    assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).isEmpty());
  }

  // --- a blank handle is a no-op -------------------------------------------

  @Test
  void a_blank_handle_is_a_no_op() {
    properties.setAdminHandle("");

    seed().run(null);

    verifyNoInteractions(admins);
    verifyNoInteractions(hasher);
  }

  /**
   * {@link AdminSeed#run} guards {@code null} explicitly, not only {@code isBlank()} — {@link
   * AuthProperties#getAdminHandle()} never actually returns {@code null} in production, but the
   * guard is written to cover it and a test that never exercises that half of the condition is a
   * branch nothing here holds.
   */
  @Test
  void a_null_handle_is_also_a_no_op() {
    properties.setAdminHandle(null);

    seed().run(null);

    verifyNoInteractions(admins);
    verifyNoInteractions(hasher);
  }

  @Test
  void a_handle_that_is_only_whitespace_is_a_no_op() {
    properties.setAdminHandle("   ");

    seed().run(null);

    verifyNoInteractions(admins);
    verifyNoInteractions(hasher);
  }

  // --- an admin under a different handle refuses the boot -----------------

  /**
   * The defect this task fixes: an operator changes {@code PLOWSHARE_ADMIN_HANDLE} against a
   * database that already has an admin under the old one. {@link AdminSeed} used to read that as
   * "no admin under this handle yet" and seed a second row, leaving the first live under its
   * original password. It must now refuse the boot instead, and never touch {@link PasswordHasher}
   * or {@link AdminStore#create} getting there — the environment is not even asked for a password,
   * since the refusal fires before that read.
   */
  @Test
  void an_admin_under_a_different_handle_refuses_the_boot() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(admins.handleOfAnyOtherAdmin("root")).thenReturn(Optional.of("original-admin"));

    IllegalStateException refusal =
        assertThrows(IllegalStateException.class, () -> seed().run(null));

    assertTrue(
        refusal.getMessage().contains("PLOWSHARE_ADMIN_HANDLE"),
        "the refusal did not name the variable: " + refusal.getMessage());
    assertTrue(
        refusal.getMessage().contains("root"),
        "the refusal did not say what the variable is now: " + refusal.getMessage());
    assertTrue(
        refusal.getMessage().contains("original-admin"),
        "the refusal did not name the account already in the database: " + refusal.getMessage());
    verify(admins, never()).create(anyString(), anyString());
    verifyNoInteractions(hasher);
    verify(environment, never()).getProperty(AdminSeed.ADMIN_PASSWORD_ENV);
  }

  /**
   * Same handle as what is already seeded: still the ordinary no-op, not the new refusal — {@link
   * AdminStore#handleOfAnyOtherAdmin} is never even asked, because {@link AdminStore#byHandle}
   * already answered.
   */
  @Test
  void the_same_handle_still_no_ops_and_does_not_ask_whether_another_admin_exists() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(new AdminRecord("root", "existing-hash", false, OffsetDateTime.now())));

    seed().run(null);

    verify(admins, never()).create(anyString(), anyString());
    verify(admins, never()).handleOfAnyOtherAdmin(anyString());
    verifyNoInteractions(hasher);
    verify(environment, never()).getProperty(AdminSeed.ADMIN_PASSWORD_ENV);
  }

  // --- an unset password refuses the boot ----------------------------------

  @Test
  void an_unset_password_refuses_the_boot() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn(null);

    IllegalStateException refusal =
        assertThrows(IllegalStateException.class, () -> seed().run(null));

    assertTrue(
        refusal.getMessage().contains("PLOWSHARE_ADMIN_PASSWORD"),
        "the refusal did not name the variable to set: " + refusal.getMessage());
    verify(admins, never()).create(anyString(), anyString());
    verifyNoInteractions(hasher);
  }

  @Test
  void a_blank_password_refuses_the_boot_the_same_way_as_an_unset_one() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn("   ");

    assertThrows(IllegalStateException.class, () -> seed().run(null));

    verify(admins, never()).create(anyString(), anyString());
    verifyNoInteractions(hasher);
  }

  // --- a placeholder password refuses the boot -----------------------------

  @Test
  void a_placeholder_password_refuses_the_boot() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn("changeme");

    IllegalStateException refusal =
        assertThrows(IllegalStateException.class, () -> seed().run(null));

    assertTrue(
        refusal.getMessage().contains("placeholder"),
        "the refusal did not say why a placeholder was refused: " + refusal.getMessage());
    verify(admins, never()).create(anyString(), anyString());
    verifyNoInteractions(hasher);
  }

  /**
   * The comparison is case- and whitespace-insensitive — {@link
   * PasswordPolicy#isObviousPlaceholder(String)}'s own contract — so a pasted value with stray
   * capitalisation or surrounding whitespace is still caught.
   */
  @Test
  void a_placeholder_password_is_still_caught_with_different_casing_and_padding() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn("  ChangeMe  ");

    assertThrows(IllegalStateException.class, () -> seed().run(null));

    verify(admins, never()).create(anyString(), anyString());
  }

  // --- a second boot with an admin present is a no-op ----------------------

  /**
   * The idempotency {@link AdminSeed}'s own class note argues for: an operator who set the pair
   * once, restarted, and then removed {@code PLOWSHARE_ADMIN_PASSWORD} from the environment — the
   * ordinary thing to do once the account exists — must not have this boot refused over a variable
   * it no longer needs. So this asserts not merely that {@code run} returns without throwing, but
   * that it never even asks {@link Environment} for the password at all: a boot that checked the
   * password and merely chose not to act on it would still be reachable by every failure mode
   * above, quietly, on a boot where nobody expects one.
   */
  @Test
  void a_second_boot_with_an_admin_present_is_a_no_op_and_does_not_recheck_or_reset_the_password() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(new AdminRecord("root", "existing-hash", false, OffsetDateTime.now())));

    seed().run(null);

    verify(admins, never()).create(anyString(), anyString());
    verifyNoInteractions(hasher);
    verify(environment, never()).getProperty(AdminSeed.ADMIN_PASSWORD_ENV);
  }

  /**
   * And not reset even when the environment still carries a placeholder or nothing at all — the
   * same point as the test above, pinned against the two values that would have refused a first
   * boot, so a change to the order of the checks in {@link AdminSeed#run} cannot silently reach the
   * environment before the existence check for a handle that is already seeded.
   */
  @Test
  void a_second_boot_does_not_refuse_even_if_the_environment_would_have_refused_a_first_one() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(new AdminRecord("root", "existing-hash", false, OffsetDateTime.now())));
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn("changeme");

    seed().run(null);

    verify(admins, never()).create(anyString(), anyString());
  }

  // --- the created row carries must_change_password ------------------------

  /**
   * Against a real, migrated Postgres: the row {@link AdminSeed} creates carries {@code
   * must_change_password} true, which is {@code V37__admins.sql}'s own default and the property the
   * rest of this slice — {@code AuthController#login} withholding a refresh token, {@code POST
   * /v1/auth/password} clearing it — depends on being true from the very first row. {@link
   * AdminStoreTest #must_change_password_defaults_true_and_round_trips} pins the column's default
   * directly; this pins that {@link AdminSeed} actually reaches it through the ordinary {@link
   * AdminStore#create(String, String)} path rather than some other insert this table would also
   * accept.
   */
  @Test
  void a_real_password_creates_the_account_carrying_must_change_password() {
    AdminStore realAdmins = new AdminStore(jdbc);
    PasswordHasher realHasher = new PasswordHasher();
    properties.setAdminHandle("root");
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV))
        .thenReturn("a genuinely chosen passphrase, not a default");

    new AdminSeed(realAdmins, realHasher, properties, environment).run(null);

    AdminRecord created =
        realAdmins
            .byHandle("root")
            .orElseThrow(() -> new AssertionError("AdminSeed did not create the row at all"));
    assertTrue(
        created.mustChangePassword(),
        "the row AdminSeed created did not carry must_change_password, so a seeded"
            + " password could go on being the real one forever");
  }

  // --- the handle a mocked create() actually receives is trimmed -----------

  @Test
  void a_real_password_creates_the_account_with_the_hasher_s_output() {
    properties.setAdminHandle("root");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV))
        .thenReturn("a genuinely chosen passphrase");
    when(hasher.hash(any(char[].class))).thenReturn("argon2-hash-stand-in");

    seed().run(null);

    verify(admins).create("root", "argon2-hash-stand-in");
  }

  /**
   * {@code " admin "} seeds {@code admin}, not a row nobody can type at the login form — see the
   * class note on why the handle is trimmed.
   */
  @Test
  void a_padded_handle_is_trimmed_before_it_is_looked_up_or_created() {
    properties.setAdminHandle("  root  ");
    when(admins.byHandle("root")).thenReturn(Optional.empty());
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV))
        .thenReturn("a genuinely chosen passphrase");
    when(hasher.hash(any(char[].class))).thenReturn("argon2-hash-stand-in");

    seed().run(null);

    verify(admins).byHandle("root");
    verify(admins).create("root", "argon2-hash-stand-in");
  }

  // --- FIX 4: password set, handle blank, silently no-op no longer --------

  /**
   * The trap this class used to leave: an operator who sets only {@code PLOWSHARE_ADMIN_PASSWORD}
   * boots clean and has no admin, with nothing anywhere saying so. {@link AdminSeed#run} now warns,
   * naming both variables, before returning at the same early exit.
   */
  @Test
  void a_password_set_with_no_handle_warns_and_names_both_variables() {
    properties.setAdminHandle("");
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV))
        .thenReturn("a genuinely chosen passphrase");

    Logger seedLog = (Logger) LoggerFactory.getLogger(AdminSeed.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    seedLog.addAppender(captured);
    try {
      seed().run(null);
    } finally {
      seedLog.detachAppender(captured);
    }

    assertTrue(
        captured.list.stream()
            .anyMatch(
                event ->
                    event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("PLOWSHARE_ADMIN_PASSWORD")
                        && event.getFormattedMessage().contains("PLOWSHARE_ADMIN_HANDLE")),
        "no warning named both PLOWSHARE_ADMIN_PASSWORD and PLOWSHARE_ADMIN_HANDLE when"
            + " a password was set with no handle to go with it — logged events: "
            + captured.list);
    verifyNoInteractions(admins);
  }

  /**
   * No password and no handle: the ordinary, silent case, and this pins that the warning above did
   * not turn every blank-handle boot noisy.
   */
  @Test
  void neither_variable_set_warns_about_nothing() {
    properties.setAdminHandle("");
    when(environment.getProperty(AdminSeed.ADMIN_PASSWORD_ENV)).thenReturn(null);

    Logger seedLog = (Logger) LoggerFactory.getLogger(AdminSeed.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    seedLog.addAppender(captured);
    try {
      seed().run(null);
    } finally {
      seedLog.detachAppender(captured);
    }

    assertTrue(
        captured.list.isEmpty(),
        "a boot with neither variable set logged something from AdminSeed: " + captured.list);
  }
}
