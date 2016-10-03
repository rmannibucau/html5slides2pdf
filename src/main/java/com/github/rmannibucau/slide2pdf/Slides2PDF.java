package com.github.rmannibucau.slide2pdf;

import com.google.common.base.Predicate;
import lombok.NoArgsConstructor;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.loader.Files;
import org.apache.openejb.util.JarExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.tomee.embedded.Configuration;
import org.apache.tomee.embedded.Container;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;
import org.kohsuke.args4j.ParserProperties;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.naming.NamingException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.stream.Stream;

import static lombok.AccessLevel.PRIVATE;
import static org.apache.openejb.loader.Files.delete;
import static org.apache.ziplock.JarLocation.jarFromRegex;

@NoArgsConstructor(access = PRIVATE)
public class Slides2PDF {
    private static final int WAIT_TIMEOUT = 120; // sec

    @Option(name = "-i", aliases = "--input", usage = "Source html file", required = true)
    private String source;

    @Option(name = "-o", aliases = "--output", usage = "PDF output path", required = true)
    private String target;

    @Option(name = "-d", aliases = "--doc-base", usage = "base directory containing the source file", required = true)
    private String base;

    @Option(name = "-t", aliases = "--type", usage = "type of html slides")
    private String type = "revealjs";

    @Option(name = "-w", aliases = "--work-dir", usage = "temporary folder where storing pictures")
    private String work = new File(System.getProperty("java.io.tmpdir"), Slides2PDF.class.getName() + "-" + System.currentTimeMillis()).getAbsolutePath();

    @Option(name = "-c", aliases = "--collapse-animations", usage = "If a slide has some animations should all representations of this slide be collapsed (taking last one)")
    private boolean collapseAnimations = true;

    @Option(name = "-s", aliases = "--size", usage = "size of the canvas (XxY syntax)")
    private String size = "800x600";

    @Option(name = "-m", aliases = "--media-box", usage = "media box to use for image pages, default to A4")
    private String mediaBox = "A4";
    private PDRectangle mediaBoxRuntime;

    public static void main(final String[] args) {
        final Slides2PDF main = new Slides2PDF();
        final CmdLineParser parser = new CmdLineParser(main, ParserProperties.defaults().withUsageWidth(80));
        try {
            parser.parseArgument(args);
        } catch (final CmdLineException e) {
            System.err.println("java -jar html5slides2pdf.jar com.github.rmannibucau.slide2pdf.Slides2PDF [options...] arguments...");
            parser.printUsage(System.err);
            System.err.println();
            System.err.println("  Example: java SampleMain" + parser.printExample(OptionHandlerFilter.ALL));
            System.err.println();
            e.printStackTrace();
            return;
        }


        if (!"revealjs".equalsIgnoreCase(main.type)) {
            throw new IllegalArgumentException("Only reveal is supported ATM");
        }

        try {
            final PDRectangle rectangle = PDRectangle.class.cast(PDRectangle.class.getField(main.mediaBox.toUpperCase(Locale.ENGLISH)).get(null));
            // rotate the size since we will be in 99% of the time in landscape mode
            main.mediaBoxRuntime = new PDRectangle(rectangle.getHeight(), rectangle.getWidth());
        } catch (final IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalArgumentException("Bad media box, maybe use A4?");
        }

        try {
            main.run();
        } catch (final RuntimeException re) {
            re.printStackTrace();
            System.exit(-1);
        }
    }

    private void run() {
        try {
            final File output = new File(work, "captures/");
            Files.mkdirs(output);

            final Configuration configuration = new Configuration()
                    .randomHttpPort();
            try (final Container container = new Container(configuration)) {
                container.deploy("", new File(base), true);

                int counter = 1;
                final PhantomJSDriverService service = findService();
                final PhantomJSDriver driver = new PhantomJSDriver(service, DesiredCapabilities.chrome());
                driver.manage().window().setSize(new Dimension((int) mediaBoxRuntime.getWidth(), (int) mediaBoxRuntime.getHeight()));

                try {
                    driver.get("http://localhost:" + configuration.getHttpPort() + "/" + source);

                    // change transitions to avoid to break the captures if we go too fast compared to transition duration
                    driver.executeScript("(function () {" +
                            "  var slide2pdfConfig = Reveal.getConfig();" +
                            // here are our overrides
                            "  slide2pdfConfig.transition='none';" +
                            "  slide2pdfConfig.none='none';" +
                            // and relaunch it
                            "  Reveal.configure(slide2pdfConfig);" +
                            "})();");

                    new WebDriverWait(driver, WAIT_TIMEOUT).until(new Predicate<WebDriver>() { // wait plugins to be loaded
                        @Override
                        public boolean apply(final WebDriver webDriver) {
                            return Boolean.class.cast(driver.executeScript("return Reveal.isReady();"));
                        }
                    });

                    do {
                        if (collapseAnimations) {
                            final String current = getCurrentSlide(driver);
                            while (hasNext(driver)) {
                                moveToNext(driver);
                                if (!current.equals(getCurrentSlide(driver))) {
                                    moveToPrevious(driver);
                                    break;
                                }
                            }
                        }

                        // ensure slide is loaded when we do the capture
                        driver.executeScript("Slide2PDF = {ready: false};");
                        driver.executeScript("setTimeout(function () {Slide2PDF.ready = true;}, 0);");
                        new WebDriverWait(driver, WAIT_TIMEOUT).until(new Predicate<WebDriver>() { // wait plugins to be loaded
                            @Override
                            public boolean apply(final WebDriver webDriver) {
                                return Boolean.class.cast(driver.executeScript("return Slide2PDF.ready;"));
                            }
                        });

                        // do the actual image capture
                        doCapture(driver, output, counter);

                        // log state for big slideshow to avoid to wait and wonder what is happening
                        final String slide = getCurrentSlide(driver);
                        System.out.println("Slide '" + slide + "' (progress=" + driver.executeScript("return Reveal.getProgress();") + ")");
                        counter++;
                    } while (moveToNext(driver));
                } finally {
                    driver.close();
                    service.stop();
                }
            } catch (final OpenEJBException | IOException | NamingException e) {
                throw new IllegalStateException(e);
            }

            createPDF(output);
        } finally {
            Files.deleteOnExit(new File(work));
        }
    }

    private String getCurrentSlide(PhantomJSDriver driver) {
        final String currentUrl = driver.getCurrentUrl();
        return currentUrl.substring(currentUrl.lastIndexOf('#') + 2);
    }

    private void createPDF(final File captures) {
        final File output = new File(target);
        final File[] images = captures.listFiles((dir, name) -> name.endsWith("png"));
        if (images == null) {
            throw new IllegalStateException("No capture taken");
        }

        final PDDocument doc = new PDDocument();
        Stream.of(images)
                .sorted((o1, o2) -> o1.getName().compareTo(o2.getName())) // we made the name to be able to sort it this way
                .forEach(image -> {
                    final PDPage page = new PDPage();
                    page.setMediaBox(mediaBoxRuntime);
                    page.setCropBox(mediaBoxRuntime);
                    doc.addPage(page);

                    try (final PDPageContentStream content = new PDPageContentStream(doc, page)) {
                        final PDImageXObject img = PDImageXObject.createFromFile(image.getAbsolutePath(), doc);
                        content.drawImage(img, 0, 0, img.getWidth(), img.getHeight());
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        try {
            doc.save(output);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                doc.close();
                System.out.println("Wrote " + output);
            } catch (final IOException e) {
                if (output.isFile()) {
                    delete(output);
                }
            }
        }
    }

    private void doCapture(final PhantomJSDriver driver, final File root, final int counter) {
        final File output = new File(root, String.format("images-%03d.png", counter));
        try (final OutputStream fos = new FileOutputStream(output)) {
            fos.write(driver.getScreenshotAs(OutputType.BYTES));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean moveToNext(final PhantomJSDriver driver) {
        final Boolean hasNext = hasNext(driver);
        if (hasNext) {
            driver.executeScript("Reveal.next();");
        }
        return hasNext;
    }

    private boolean moveToPrevious(final PhantomJSDriver driver) {
        final Boolean hasNext = hasNext(driver);
        if (hasNext) {
            driver.executeScript("Reveal.prev();");
        }
        return hasNext;
    }

    private Boolean hasNext(final PhantomJSDriver driver) {
        return !Boolean.class.cast(driver.executeScript("return Reveal.isLastSlide();"));
    }

    private PhantomJSDriverService findService() {
        final File phantomJs = new File(work, "phantomjs");
        try {
            JarExtractor.extract(jarFromRegex("arquillian-phantom-binary.*" + findSuffix()), phantomJs);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        final File exec = new File(phantomJs, "bin/phantomjs" + (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win") ? ".exe" : ""));
        if (!exec.isFile()) {
            throw new IllegalStateException("Didn't find phantomjs executable in " + phantomJs);
        }
        exec.setExecutable(true);

        return new PhantomJSDriverService.Builder()
                .withLogFile(new File(work, "ghostdriver.log"))
                .usingPhantomJSExecutable(exec)
                .usingAnyFreePort()
                .build();
    }

    private static String findSuffix() {
        final String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        if (os.contains("mac")) {
            return "macosx.jar";
        }
        if (os.contains("win")) {
            return "windows.jar";
        }
        if (os.contains("linux")) {
            return "linux-64.jar";
        }
        return ".jar"; // fine if a single impl is there
    }
}
