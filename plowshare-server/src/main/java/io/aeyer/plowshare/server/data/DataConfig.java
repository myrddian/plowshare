package io.aeyer.plowshare.server.data;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link DataLayout} and, in doing so, decides where this server's own files are before
 * anything asks.
 *
 * <p>{@link DataLayout} takes no stereotype annotation of its own, on {@code Archive}'s reasoning:
 * it stays framework-free, and whatever wires it supplies the mechanism it will not name — here,
 * the one property.
 *
 * <p><b>{@link DataLayout#initialise} runs in the bean method, not in an {@code
 * ApplicationReadyEvent} listener.</b> The three things it refuses — a path that is a file, an
 * unmarked directory that already holds something, a marker from a layout this version does not
 * know — are all mistakes an operator made while watching, and the only other report they would get
 * arrives inside the first retention sweep that ejects anything, in production, once. It is {@code
 * AgentsConfig}'s rule for {@code global/agents} applied to the directory this server
 * <em>writes</em>: there and wrong stops the boot.
 *
 * <p>A blank property is not one of the three. It is {@link DataLayout#NONE}, the state of every
 * test context in this repository, and nothing is created — see {@link DataProperties}, which
 * argues why the blank has to be the default rather than a value in {@code application.yml}.
 */
@Configuration
@EnableConfigurationProperties(DataProperties.class)
public class DataConfig {

  @Bean
  public DataLayout dataLayout(DataProperties props) {
    String dir = props.getDir();
    return dir == null || dir.isBlank()
        ? DataLayout.NONE
        : new DataLayout(Path.of(dir)).initialise();
  }
}
