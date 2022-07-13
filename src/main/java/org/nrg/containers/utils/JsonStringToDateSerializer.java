package org.nrg.containers.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;

import static org.nrg.containers.utils.JsonDateSerializer.DATE_FORMAT;

/**
 * @author Mohana Ramaratnam
 *
 */
public class JsonStringToDateSerializer extends JsonSerializer<String> {
	private static final Pattern NUMERIC_TIMESTAMP = Pattern.compile("^\\d{12,}$");  // Twelve or more digits and that's all

	private static final int NUM_DIGITS_IN_NANOSECOND_VALUE = 16;
	private static final int NUM_DIGITS_TO_TRUNCATE_NANO_TO_MILLI = 6;

    @Override
    public void serialize(String value, JsonGenerator jgen, SerializerProvider provider) throws IOException,
            JsonProcessingException {
    		if (StringUtils.isBlank(value)) {
				return;
			}

			if (!NUMERIC_TIMESTAMP.matcher(value).matches()) {
				// Timestamp isn't numeric. Return as-is.
				jgen.writeString(value);
				return;
			}

			if (value.length() >= NUM_DIGITS_IN_NANOSECOND_VALUE) {
				// This value is in nanoseconds - convert to milliseconds
				value = value.substring(0, value.length() - NUM_DIGITS_TO_TRUNCATE_NANO_TO_MILLI);
			}

    		long longVal = Long.parseLong(value);

    		Date longAsDate = new Date(longVal);
    		jgen.writeString(DATE_FORMAT.format(longAsDate));
    }
    
    public static void main(String[] args) {
    	long longVal = Long.parseLong("1542051871478");
		Date longAsDate = new Date(longVal);
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS Z");
		//System.out.println("String to Date: " + format.format(longAsDate));
		try {
		    Date d = format.parse("2018-11-03T12:45:38.615-05:00");
		    long milliseconds = d.getTime();
		} catch (Exception e) {
		    e.printStackTrace();
		}		
    }

}
