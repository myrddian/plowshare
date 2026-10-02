package io.aeyer.plowshare.server.images;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What bounds an image, and nothing else — there is no key saying where they go.
 *
 * <p>The directory is {@code DataLayout}'s and has no override; {@code
 * DataLayout.imagesFor} argues why, and it is the same "convention over
 * configuration" the data directory landed with, applied to a tenant that
 * arrived after it rather than before.
 */
@ConfigurationProperties(prefix = "plowshare.images")
public class ImagesProperties {

    /**
     * The largest image this deployment will hold, in <b>raw</b> bytes.
     *
     * <h2>Raw and not base64, decided rather than defaulted</h2>
     *
     * <p>The two differ by 4/3 and the spec left it open, so: <b>this measures
     * the file</b>. The reason is that it is the number a person can check. An
     * operator raising the cap is looking at a file on a disk and at {@code ls
     * -l}; an uploader who is refused wants to know how much smaller to make the
     * file they have. A cap on the encoded payload would make the same 5 MiB
     * PNG acceptable or not depending on an encoding it never sees, and would
     * have to be explained every time it was quoted.
     *
     * <p><b>The consequence is stated rather than discovered:</b> at the default
     * the largest {@code data:} URI this server can put on the wire is about
     * 7.0 MB of base64, plus the message around it. A deployment sizing a
     * request budget should use that number and not this one.
     *
     * <h2>Refused and never downscaled</h2>
     *
     * <p>An image over the cap is refused at the upload. Re-encoding it smaller
     * would mean the model was shown something no person chose and nothing
     * recorded — a different picture under the same UID — and every answer about
     * it would be an answer about the server's resampling as much as about the
     * image. This project has the same rule about sampling parameters and about
     * tool arguments: what was actually sent is what was written.
     *
     * <h2>5 MiB, and why that is a ceiling rather than a target</h2>
     *
     * <p>It was 250 KiB, which was the figure the design decided before anything
     * had been built against it. Two things moved it. A screenshot of a full
     * desktop does not reliably fit in 250 KiB, and this feature exists so a
     * model can be shown one; and 5 MiB is what the vision endpoints in the
     * fleet and the hosted APIs converge on as a per-image limit, so a cap above
     * it would only mint uploads that fail a hop further away.
     *
     * <p>It is a ceiling and not a size to aim at. A typical screenshot is well
     * under a megabyte, and the guidance every vendor gives for mockups and UI
     * captures is nearer 2 MB than 5. Nothing here downscales toward it — see
     * above — so the cap only ever decides refusal, never quality.
     *
     * <p><b>What now binds is not this number.</b> Storage was never the scarce
     * thing; context is. {@code Compaction} counts no image tokens at all, so a
     * conversation holding a large picture is one whose true size nothing in
     * this server can report. Raising this cap twentyfold raised the cost of
     * that gap by the same factor and did not create it. Until image token
     * accounting exists, a deployment that shows models many large pictures
     * should expect the ceiling to be reached somewhere that cannot explain
     * itself.
     *
     * <p>Raisable, and deliberately not lowerable to zero: a cap of zero refuses
     * everything, and a deployment that wants to hold no images says so by
     * keeping no data directory.
     */
    private int maxBytes = 5 * 1024 * 1024;

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }
}
