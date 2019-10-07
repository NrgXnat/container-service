package org.nrg.containers.utils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * @author Mohana Ramaratnam
 *
 */
public class JsonStringToDateSerializer extends JsonSerializer<String> {
    private static final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    @Override
    public void serialize(String value, JsonGenerator jgen, SerializerProvider provider) throws IOException,
            JsonProcessingException {
    		if (value == null || value.equals(""))
    			return;
    		long longVal = Long.parseLong(value);
    		Date longAsDate = new Date(longVal);
    		jgen.writeString(format.format(longAsDate));
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
