package ai.reviewforge.runner.run;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Reads Surefire XML so results come from the build's own report, not from stdout scraping. */
@Component
public class SurefireReportParser {

    public Counts parse(Path projectRoot) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;

        for (Path report : reports(projectRoot)) {
            try (InputStream input = Files.newInputStream(report)) {
                Element suite = documentBuilder().parse(input).getDocumentElement();
                tests += attribute(suite, "tests");
                failures += attribute(suite, "failures");
                errors += attribute(suite, "errors");
                skipped += attribute(suite, "skipped");
            } catch (IOException | SAXException | ParserConfigurationException exception) {
                // An unreadable report contributes nothing; the exit code still decides the run.
            }
        }

        int failed = failures + errors;
        return new Counts(tests, Math.max(0, tests - failed - skipped), failed, skipped);
    }

    private List<Path> reports(Path projectRoot) {
        try (Stream<Path> paths = Files.walk(projectRoot, 12)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("surefire-reports"))
                    .filter(path -> path.getFileName().toString().startsWith("TEST-")
                            && path.getFileName().toString().endsWith(".xml"))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // The default Xerces handler prints malformed untrusted XML to stderr before throwing.
        // Keep runner logs clean and let parse() handle the exception as an unreadable report.
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }

    private int attribute(Element element, String name) {
        String value = element.getAttribute(name);
        try {
            return value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public record Counts(int tests, int passed, int failed, int skipped) {
    }
}
