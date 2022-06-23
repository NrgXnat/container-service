package org.nrg.containers.model.xnat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class XnatModelTest {
    private static final String FILE_JSON = "{\"name\":\"file.txt\", \"type\":\"File\", \"path\":\"/path/to/files/file.txt\", " +
            "\"tags\":[\"squishy\",\"jovial\"], \"format\":\"TEXT\", \"content\":\"TEXT\"}";
    private static final String RESOURCE_JSON = "{\"id\":\"1\", \"type\":\"Resource\", \"label\":\"a_resource\", " +
            "\"directory\":\"/path/to/files\", \"files\":[" + FILE_JSON + "]}";

    private static final String SESSION_JSON = "{\"id\":\"E1\", \"type\":\"Session\", \"label\":\"a_session\", " +
            "\"xsiType\":\"xnat:fakesessiondata\", \"resources\":[" + RESOURCE_JSON + "]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ParseContext parseContext = JsonPath.using(Configuration.builder()
            .jsonProvider(new JacksonJsonProvider(mapper))
            .mappingProvider(new JacksonMappingProvider(mapper))
            .build());

    @Test
    public void testDeserializeFile() throws Exception {
        final XnatFile file = mapper.readValue(FILE_JSON, XnatFile.class);
        assertThat(file.getName(), is("file.txt"));
        assertThat(file.getPath(), is("/path/to/files/file.txt"));
        assertThat(file.getTags(), contains("squishy", "jovial"));
        assertThat(file.getFormat(), is("TEXT"));
        assertThat(file.getContent(), is("TEXT"));
    }

    @Test
    public void testDeserializeResource() throws Exception {
        final XnatFile file = mapper.readValue(FILE_JSON, XnatFile.class);
        final Resource resource = mapper.readValue(RESOURCE_JSON, Resource.class);
        assertThat(resource.getId(), is("1"));
        assertThat(resource.getLabel(), is("a_resource"));
        assertThat(resource.getDirectory(), is("/path/to/files"));
        assertThat(resource.getFiles(), contains(file));
    }

    @Test
    public void testDeserializeSession() throws Exception {
        final Resource resource = mapper.readValue(RESOURCE_JSON, Resource.class);
        final Session session = mapper.readValue(SESSION_JSON, Session.class);
        assertThat(session.getId(), is("E1"));
        assertThat(session.getLabel(), is("a_session"));
        assertThat(session.getXsiType(), is("xnat:fakesessiondata"));
        assertThat(session.getResources(), contains(resource));
        assertNull(session.getScans());
        assertNull(session.getAssessors());
    }

    @Test
    public void testJsonPathOnXnatObjects() throws Exception {
        final Resource expected = mapper.readValue(RESOURCE_JSON, Resource.class);
        final List<Resource> resources = parseContext.parse(SESSION_JSON).read("$.resources[*]", new TypeRef<List<Resource>>(){});

        assertThat(resources, hasSize(1));
        assertThat(resources.get(0), instanceOf(Resource.class));
        assertThat(resources, contains(expected));
    }

    @Test
    public void testCommandInputJsonPath() throws Exception {
        final String scantype = "SCANTYPE";

        final String commandJson =
                "{\"inputs\": [" +
                        "{\"name\": \"T1-scantype\", \"description\": \"Scantype of T1 scans\", " +
                        "\"type\": \"string\", " +
                        "\"value\": \"" + scantype + "\"}"
                        + "]}";

        final List<String> results = parseContext.parse(commandJson).read("$.inputs[?(@.name == 'T1-scantype')].value");
        assertThat(results, contains(scantype));
    }

    @Test
    public void testPredicateWithList() throws Exception {
        final String scanRuntimeJson =
                "{\"id\": \"scan1\", \"type\":\"Scan\", " +
                        "\"scan-type\": \"SCANTYPE\"" +
                        "}";
        final String sessionRuntimeJson =
                "{\"id\": \"session1\", \"label\": \"session1\"," +
                        "\"scans\": [" + scanRuntimeJson + "]" +
                        "}";
        final Scan expected = mapper.readValue(scanRuntimeJson, Scan.class);

        final List<Scan> results = parseContext.parse(sessionRuntimeJson).read("$.scans[?(@.scan-type in [\"SCANTYPE\", \"OTHER_SCANTYPE\"])]", new TypeRef<List<Scan>>(){});

        assertThat(results, contains(expected));
    }
}
