package io.aeyer.plowshare.server.auth;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates this server's one admin account on first start, from an operator's
 * own environment variables — and refuses the boot rather than create one with
 * a password that could go on being the real password forever.
 *
 * <h2>Why a runner and not a migration</h2>
 *
 * <p>A Flyway migration runs once, at whatever moment {@code flyway migrate}
 * happens to execute, and has no access to this process's environment beyond
 * whatever {@code application.yml} already resolved into the connection
 * itself — {@code MigrationsAreImmutableTest} then freezes it forever the
 * first time it ships. An admin's handle and password are read at <em>this
 * boot's</em> environment, not at whatever boot first ran the migration chain,
 * and a value baked into an immutable file the first time this feature shipped
 * would be exactly the permanent default this class exists to refuse. An
 * {@link ApplicationRunner} reads the live environment on every boot instead,
 * which is what lets {@link #run(ApplicationArguments)} decide fresh each time
 * whether there is anything to do.
 *
 * <h2>Opt-in, on {@link AuthProperties#getAdminHandle()}'s reasoning</h2>
 *
 * <p>A blank {@link AuthProperties#getAdminHandle()} means no operator has
 * asked for an admin account yet, and this class does nothing at all — no
 * lookup, no password check, no refusal. That is what keeps every
 * full-application test in this repository booting unchanged, and what keeps
 * a deployment still on the operator token booting exactly as it always has:
 * see that property's javadoc for the account in full.
 *
 * <h2>The password is read from {@link Environment}, not from a bean field</h2>
 *
 * <p>The handle above is a name and costs nothing to hold; the password is a
 * secret a person chose, and this class deliberately does not give it a
 * second home. {@link AuthProperties} is a singleton that lives for the whole
 * process, so a {@code String} field on it would sit in the heap for as long
 * as the server runs — long after the one call that needs it has returned —
 * and would show up in a heap dump taken at any later point. {@link
 * #run(ApplicationArguments)} instead asks {@code environment} for {@code
 * PLOWSHARE_ADMIN_PASSWORD} at the moment it is needed, the same way {@code
 * RuntimeConfigController} reads a live key through a plain {@link
 * Environment} rather than through a properties bean, and keeps no field and
 * no reference to it once this method returns.
 *
 * <p><b>This narrows the exposure; it does not remove it.</b> A Java {@code
 * String} cannot be wiped — unlike the {@code char[]} {@link PasswordHasher}
 * takes and zeroes, {@code String} is immutable and may be interned, so
 * nothing in this process can unmake one once it exists. Spring's own {@code
 * systemEnvironment} property source already holds a copy of this process's
 * whole environment for the process's entire lifetime, which is a fact about
 * the JVM and the platform that no code in this server can change. What
 * reading through {@code environment} here buys, honestly stated, is that
 * <em>this class</em> does not add a second, longer-lived copy of its own on
 * top of that one — the password is not held longer than the seeding needs,
 * not "never in the heap".
 *
 * <p><b>And it does not narrow how the value could have arrived, either.</b>
 * {@link Environment#getProperty(String)} searches every property source
 * Spring Boot registers, which includes command-line arguments: {@code
 * --PLOWSHARE_ADMIN_PASSWORD=...} on the command line that started this
 * process works exactly like the environment variable of the same name, and a
 * command line is visible to every other local user through {@code ps} for as
 * long as this process runs. Reading through {@code Environment} rather than
 * through {@code System.getenv} directly is what makes the variable's name
 * one constant instead of two — it is not a choice that closes this exposure,
 * and nothing in this class can: an operator who wants the password off the
 * command line has to put it in the environment instead, not in an argument.
 *
 * <h2>The two mechanisms against a seeded password persisting</h2>
 *
 * <p>Once a handle is set and no admin exists under it yet, a seeded password
 * must not be able to become the permanent one by nobody rotating it — a
 * default nobody changes is worse than the operator token it replaces,
 * because it looks solved. Both of the slice's mechanisms are here:
 *
 * <ol>
 *   <li><b>The boot is refused</b> if {@code PLOWSHARE_ADMIN_PASSWORD} is
 *       blank or an obvious placeholder, in the shape {@link AuthConfig}
 *       already refuses one for {@code spring.mvc.servlet.path} and {@code
 *       bin/plowshare} already refuses one for a blank model name: naming the
 *       variable and saying what to set, because the reader is an operator at
 *       3am and not someone who is about to go re-read this class.
 *   <li><b>The account is created with {@code must_change_password}
 *       true</b> — {@code V37__admins.sql}'s default — so that even a password
 *       that passed the check above cannot go on being the real password past
 *       the first login. This class only has to make sure every row it writes
 *       carries it; {@code AuthController#login} reads it and withholds the
 *       refresh token accordingly, and {@code AuthController#changePassword}
 *       — {@code POST /v1/auth/password} — is what actually clears it. That
 *       endpoint used to not exist at all: this design assigned storing and
 *       enforcing the flag and never assigned a way to turn it off, which made
 *       every account this class seeds a permanent fifteen-minute-access,
 *       no-refresh session with no escape short of hand-editing the database.
 *       {@code AdminStore#changePassword(String, String)} is the write it
 *       needed, and {@link PasswordPolicy#isObviousPlaceholder(String)} is the
 *       same refusal below, factored out so the endpoint that changes a
 *       password and this class that seeds one cannot each carry their own
 *       copy of the list.
 * </ol>
 *
 * <h2>Idempotent, and the password is not re-checked after the first boot</h2>
 *
 * <p>{@link #run(ApplicationArguments)} looks the handle up before doing
 * anything else, and does nothing further if it is already there. That is
 * deliberate: an operator who set the pair once, restarted, and then removed
 * {@code PLOWSHARE_ADMIN_PASSWORD} from their environment — the ordinary
 * thing to do once the account is no longer being created — must not have
 * their next boot refused over a variable that boot no longer needs. The
 * refusal below is reachable only on the boot that would actually create a
 * row.
 *
 * <p>{@code handle} is trimmed before either check runs, so {@code
 * PLOWSHARE_ADMIN_HANDLE=" admin "} — a stray space from a copied {@code .env}
 * line, say — seeds {@code admin} rather than a row nobody can type at the
 * login form, which only ever sends what a human typed and never the
 * surrounding whitespace of an environment file.
 *
 * <h2>Set only the password, and the handle stays blank: a silent no-op, said
 * out loud instead</h2>
 *
 * <p>{@link AuthProperties#getAdminHandle()} blank is "no operator has asked
 * for an admin account yet", by design — see that property's own javadoc —
 * and this method returns at that check before it has looked at {@code
 * PLOWSHARE_ADMIN_PASSWORD} at all. That is correct for an operator who set
 * neither variable. It is a trap for one who set only the password and
 * believes that alone is enough: the boot still succeeds, no admin is
 * created, and nothing said so — until this method started warning. See the
 * first lines of {@link #run(ApplicationArguments)} for where that warning
 * lives and why it names both variables rather than only the one that was
 * set.
 *
 * <h2>A second handle refuses the boot — a posture, not a law</h2>
 *
 * <p>Changing {@code PLOWSHARE_ADMIN_HANDLE} after the first boot does not
 * rename the existing admin — {@link #run(ApplicationArguments)} looks up the
 * <em>new</em> handle and finds nothing under it, because it is a new handle.
 * Left uncaught, that is indistinguishable from a fresh deployment, and this
 * class would seed a second row, so this server would then hold two admin
 * accounts under their own separate passwords while every other piece of
 * prose in this slice — {@code V37__admins.sql}'s own comments among them —
 * talks about "the" admin as though there could only be one. {@link
 * #run(ApplicationArguments)} now asks {@link AdminStore#handleOfAnyOtherAdmin(String)}
 * before it seeds anything, and refuses the boot on an answer, rather than
 * create that second row.
 *
 * <p><b>This enforces the one-admin claim the rest of the prose already
 * makes; it does not newly decide that claim here, and it is not permanent.</b>
 * {@link AdminStore#create(String, String)}'s own javadoc already notes that
 * {@code V37__admins.sql} does not itself stop a second row — nothing but
 * this refusal does — and that this table's shape does not need to change on
 * the day "exactly one admin" stops being true. When multi-user support
 * arrives, this check is the thing to revisit — relax it, or remove it and
 * let {@link AdminStore#create(String, String)} be called more than once on
 * purpose — not the table, which the schema already permits to hold many
 * rows.
 */
@Component
public class AdminSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeed.class);

    /**
     * The environment key read directly by {@link #run(ApplicationArguments)},
     * named once so the refusal messages below and the lookup itself cannot
     * drift apart.
     */
    static final String ADMIN_PASSWORD_ENV = "PLOWSHARE_ADMIN_PASSWORD";

    /**
     * The env key named in the warning {@link #run(ApplicationArguments)}
     * logs when a password is set with no handle — see the class note "Set
     * only the password". Not read by this class in that branch, since a
     * blank handle is exactly the value already in hand; named as a constant
     * anyway so the warning message and this comment cannot drift apart on
     * what the variable is called.
     */
    static final String ADMIN_HANDLE_ENV = "PLOWSHARE_ADMIN_HANDLE";

    private final AdminStore admins;
    private final PasswordHasher hasher;
    private final AuthProperties properties;
    private final Environment environment;

    public AdminSeed(
            AdminStore admins, PasswordHasher hasher, AuthProperties properties,
            Environment environment) {
        this.admins = admins;
        this.hasher = hasher;
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * @throws IllegalStateException if a handle was asked for and this table
     *     already has an admin under a <em>different</em> handle — naming both
     *     handles and what to do, on {@link AuthConfig}'s refusal shape — or if
     *     no admin exists under the asked-for handle yet and {@code
     *     PLOWSHARE_ADMIN_PASSWORD} is unset or an obvious placeholder, naming
     *     the variable and what to set, on the same shape
     */
    @Override
    public void run(ApplicationArguments args) {
        String handle = properties.getAdminHandle();
        // Trimmed before either check below runs — see the class note on why
        // a stray space in PLOWSHARE_ADMIN_HANDLE must not seed a row nobody
        // can type at the login form.
        handle = handle == null ? null : handle.trim();
        if (handle == null || handle.isBlank()) {
            // Nobody has asked for an admin account — the ordinary case every
            // full-application test context in this repository is in, and so
            // is any deployment that has not yet moved off the operator
            // token. See the class note "Set only the password" for the one
            // case worth telling the operator about even here: a password set
            // with no handle to go with it does nothing, silently, unless
            // this warns.
            String orphanedPassword = environment.getProperty(ADMIN_PASSWORD_ENV);
            if (orphanedPassword != null && !orphanedPassword.isBlank()) {
                log.warn("{} is set but {} is blank, so no admin account was created and this"
                                + " password was not used for anything. Set {} to the login name"
                                + " you want, or unset {} if you did not mean to seed an admin"
                                + " account.",
                        ADMIN_PASSWORD_ENV, ADMIN_HANDLE_ENV, ADMIN_HANDLE_ENV, ADMIN_PASSWORD_ENV);
            }
            return;
        }
        if (admins.byHandle(handle).isPresent()) {
            // Already seeded, on a previous boot. Idempotent, and the
            // password is deliberately not re-checked here — see the class
            // note on why an operator who has since removed the variable must
            // not have this boot refused over it.
            return;
        }

        // No admin under the handle asked for. Before treating that as "seed
        // a fresh account" — the only reading available before this check
        // existed — ask whether this table already holds one under some
        // other name: see the class note "A second handle refuses the boot".
        Optional<String> other = admins.handleOfAnyOtherAdmin(handle);
        if (other.isPresent()) {
            throw new IllegalStateException(
                    "PLOWSHARE_ADMIN_HANDLE is '" + handle + "' but this server's admins table"
                            + " already has an account under '" + other.get() + "'. Seeding a"
                            + " second account would leave '" + other.get() + "' live under its"
                            + " original password — this server supports exactly one admin, and"
                            + " nothing here renames one. If '" + other.get() + "' is still the"
                            + " account you want, set PLOWSHARE_ADMIN_HANDLE back to '"
                            + other.get() + "' and restart. If you meant to rename it, run UPDATE"
                            + " admins SET handle = '" + handle + "' WHERE handle = '"
                            + other.get() + "' and restart.");
        }

        // Read directly from the live environment rather than through a
        // properties bean field — see the class note on why a password gets
        // no second, longer-lived home in this process.
        String password = environment.getProperty(ADMIN_PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "PLOWSHARE_ADMIN_HANDLE is set to '" + handle + "' but"
                            + " PLOWSHARE_ADMIN_PASSWORD is unset, so there is no password to"
                            + " create this account with. Set PLOWSHARE_ADMIN_PASSWORD to a real"
                            + " password unique to this deployment and restart.");
        }
        if (PasswordPolicy.isObviousPlaceholder(password)) {
            throw new IllegalStateException(
                    "PLOWSHARE_ADMIN_PASSWORD is set to a common placeholder value. A seeded"
                            + " password that nobody changes is worse than the operator token it"
                            + " replaces, because it looks solved. Set PLOWSHARE_ADMIN_PASSWORD"
                            + " to a real password unique to this deployment and restart.");
        }

        String hash = hasher.hash(password.toCharArray());
        admins.create(handle, hash);
        // Never the password or the hash: this line exists to say an account
        // was made, not to describe it. must_change_password is true on every
        // row this method writes, per V37__admins.sql's default, so the log
        // says so rather than leaving an operator to go check.
        log.info("created admin account '{}'; it must change its password at first login",
                handle);
    }
}
