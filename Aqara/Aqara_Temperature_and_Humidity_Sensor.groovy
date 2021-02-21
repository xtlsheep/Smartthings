/*
 * ---------------------------------------------------------------------------------
 * (C) Graham Johnson (orangebucket)
 *
 * SPDX-License-Identifier: MIT
 * ---------------------------------------------------------------------------------
 *
 * Anidea for Aqara Temperature
 * ============================
 * This device handler is a reworking of the 'Xiaomi Aqara Temperature Humidity Sensor' DTH by
 * 'bspranger' that adapts it for the 'new' environment. It has been stripped of the 'tiles', 
 * custom attributes, most of its preferences, and much of the logging. The Health Check has been
 * modified and the proposed Atmospheric Pressure Measurement has been added (previously, not
 * even a custom attribute was defined).
 */

def ai_v = '21.02.15.00'
def ai_r = true

metadata
{                 
    // Use ocfDeviceType: 'oic.d.thermostat' for a thermometer icon.
    // Use ocfDeviceType: 'x.com.st.d.humidifier' for a humidifier icon.
    //
    // Use vid: 'be5ff498-cfb1-37ee-b675-5464440f45b9' to display temperature in the dashboard tile.
    // Use vid: '6bb2558f-1cf8-3625-acae-d8d6a9158b3f' to display humidity in the dashboard tile.
    //
    // Please be aware that the 'vid' may be subject to change.
	definition ( name: 'Anidea for Aqara Temperature' + ( ai_r ? '' : " ${ai_v}" ), namespace: 'orangebucket', author: 'Graham Johnson',
    			 ocfDeviceType: 'oic.d.thermostat', mnmn: 'SmartThingsCommunity', vid: 'be5ff498-cfb1-37ee-b675-5464440f45b9' )
	{
            capability 'Temperature Measurement'
            capability 'Relative Humidity Measurement'
            capability 'Atmospheric Pressure Measurement'
            capability 'Battery'
            capability 'Health Check'
            capability 'Sensor'

			// WSDCGQ11LM
			fingerprint profileId: '0104', deviceId: '5F01', inClusters: '0000, 0003, FFFF, 0402, 0403, 0405', outClusters: '0000, 0004, FFFF', manufacturer: 'LUMI', model: 'lumi.weather', deviceJoinName: 'Aqara Temperature Sensor'
	}

	preferences
    {
        input 'pressunit',   'enum',    title: 'Unit of Pressure',              description: 'Unit to use for pressure',                options: [ 'kPa', 'hPa', 'mbar', 'mmHg', 'inHg' ]
		input 'pressoffset', 'number',  title: 'Atmospheric Pressure Offset',   description: 'Adjust pressure by this many units',      range: '*..*'
		input 'humidoffset', 'number',  title: 'Humidity Offset',    			description: 'Adjust humidity by this many percent',    range: '*..*'
        input 'tempoffset',  'decimal', title: 'Temperature Offset', 			description: 'Adjust temperature by this many degrees', range: '*..*'
	}
}

// installed() is called when the device is paired, and when the device is updated in the IDE.
def installed()
{	
	logger( 'installed', 'info', '' )
         
	// Start with a twenty-four hour checkInterval for Health Check and reduce it
    // when the first 50-60 min battery report arrives.
    sendEvent( name: 'checkInterval', value: 86400, displayed: false, data: [ protocol: 'zigbee', hubHardwareId: device.hub.hardwareID ] )
   
    // The SmartThings app seems a lot happier when the attributes associated with capabilities
    // have a value, so it is a good idea to initialise them. The pressure value has been set to
    // a decimal to help detect if the app ever starts displaying decimals.
    sendEvent( name: 'temperature', 		value: 0, 	unit: 'C',   displayed: false )
    sendEvent( name: 'humidity', 			value: 100, unit: '%',   displayed: false )
    sendEvent( name: 'atmosphericPressure', value: 0.5, unit: 'kPa', displayed: false )
    sendEvent( name: 'battery', 			value: 50,  unit: '%',   displayed: false )
}

// updated() seems to be called after installed() when the handler is first installed, and when it is updated
// using the IDE, provided something has actually changed.  It runs whenever settings are updated. It was often 
// seen running twice in quick succession, so many developers chose to debounce it.
def updated()
{
	logger( 'updated', 'info', '' )
}

def logger( method, level = 'debug', message = '' )
{
	// Using log."${level}" for dynamic method invocation is now deprecated.
    switch( level )
	{
		case 'info':	log.info  "$device.displayName [$device.name] [${method}] ${message}"
        				break
        default:	    log.debug "$device.displayName [$device.name] [${method}] ${message}"
        				break
	}
}

def ping()
{
	logger( 'ping', 'info', '' )
}

// Parse incoming device messages to generate events
def parse( String description )
{
    logger( 'parse', 'debug', description )

	// getEvent automatically retrieves temp and humidity in correct unit as integer
	Map map = zigbee.getEvent( description )

	// Send message data to appropriate parsing function based on the type of report
	if ( map.name == 'temperature' )
    {
    	// Note (July 2020): zigbee.getEvent() now returns a temperature with two
        // decimal places, not an integer.  Therefore the temperature() method could
        // be rewritten.  However it isn't hurting anyone.
        
    	// Get a (possibly converted) value with more precision.
        map.value = temperature( description )
        
        // Lose the unwanted bits.
        map.remove( 'descriptionText' )
        map.remove( 'translatable' )
        
        // The unit is already in the map so need to tweak if converted.
		map.unit = temperatureScale
	} 
    else if ( map.name == 'humidity' )
    {
		map.value = humidoffset ? (int) map.value + (int) humidoffset : (int) map.value
	}
    else if ( description?.startsWith( 'catchall:' ) )
    {
		map = catchall( description )
	}
    else if ( description?.startsWith( 'read attr - raw:' ) )
    {
		map = readattr( description )
	} else
    {
		// Not really interested.
	}

	logger( 'parse', 'info', map )
    
	return createEvent( map )
}

// This goes back to the original description as zigbee.getEvent() rounded it to an integer.
def temperature( String description )
{
	def temp = ( (description - "temperature: ").trim() ) as Float
	def offset = tempoffset ? tempoffset : 0
	temp = (temp > 100) ? (100 - temp) : temp
    
    temp = ( temperatureScale == "F" ) ? ( ( temp * 1.8) + 32 ) + offset : temp + offset
    
	return temp.round(1)
}

// Check catchall for battery voltage data to pass to getBatteryResult for conversion to percentage report
Map catchall( String description )
{
	logger( 'catchall', 'debug', description )

    Map result = [:]
	def catchall = zigbee.parse( description )

	if ( catchall.clusterId == 0x0000 )
    {
		def length = catchall.data.size()
		// Original Xiaomi CatchAll does not have identifiers, first UINT16 is Battery
		if ( (catchall.data.get( 0 ) == 0x01 || catchall.data.get( 0 ) == 0x02 ) && ( catchall.data.get( 1 ) == 0xFF ) )
        {
			for ( int i = 4; i < ( length - 3 ); i++ )
            {
				if ( catchall.data.get( i ) == 0x21 )
                { // check the data ID and data type
					// next two bytes are the battery voltage
					result = battery( ( catchall.data.get( i + 2 ) << 8 ) + catchall.data.get( i + 1 ) )
					break
				}
			}
		}
	}
	return result
}

// Parse pressure report or battery report on reset button press
Map readattr( String description )
{
	logger( 'readattr', 'debug', description )
    
    Map map = [:]

	def cluster = description.split( "," ).find { it.split( ":" )[ 0 ].trim() == "cluster" }?.split( ":" )[ 1 ].trim()
	def attrid  = description.split( "," ).find { it.split( ":" )[ 0 ].trim() == "attrId"  }?.split( ":" )[ 1 ].trim()
	def value   = description.split( "," ).find { it.split( ":" )[ 0 ].trim() == "value"   }?.split( ":" )[ 1 ].trim()

	if ( ( cluster == "0403" ) && ( attrid == "0000" ) )
    {
		def result = value[ 0..3 ]
		float pressureval = Integer.parseInt( result, 16 )

		// The user manual suggests the device has a range of 30 to 110 kPa to within 0.12 kPa.
		// The supplied value seems to be in units of 0.01 kPa.
        
        def pressureunit = pressunit ? pressunit : 'kPa'
               
        if ( pressureunit == 'hPa' || pressureunit == 'mbar' )
        {
        	// 1 device unit == 0.01 kPa == 0.1 hPa == 0.1 mbar
            // 1 mbar == 1 hPa == 0.01 device units
            
            // Divide by ten and round to the nearest whole value to
            // reflect the precision of the device.
 			pressureval = ( pressureval / 10 ) as Float
			pressureval = pressureval + ( pressoffset ? pressoffset : 0.0 )

		    pressureval = pressureval.round( 0 )
        }
        else if ( pressureunit == 'mmHg' )
        {
        	// 1 device unit == 0.01 kPa = 0.07501 mmHg
            // 1 mmHg = 0.07501 device units
            
            // Multiply by 0.07501 and round to the nearest whole number to
            // reflect the precision of the device.
 			pressureval = ( pressureval * 0.07501 ) as Float
			pressureval = pressureval + ( pressoffset ? pressoffset : 0.0 )

		    pressureval = pressureval.round( 0 );
        }
        else if ( pressureunit == 'inHg' )
        {
        	// 1 device unit == 0.01 kPa = 0.00295 inHg
            // 1 inHg = 0.00295 device units
            
            // Multiply by 0.00295 and round to two places to
            // reflect the precision of the device.
 			pressureval = ( pressureval * 0.00295 ) as Float
			pressureval = pressureval + ( pressoffset ? pressoffset : 0.0 )

		    pressureval = pressureval.round( 2 );
        }
        else
        {
        	// 1 device unit == 0.01 kPa
            // 1 kPa = 100 device units
            
        	// Divide by one hundred and round to a single decimal place
            // to reflect the precision of the device.
 			pressureval = ( pressureval / 100 ) as Float
			pressureval = pressureval + ( pressoffset ? pressoffset : 0.0 )

		    pressureval = pressureval.round( 1 );
        }
        
        // The SmartThings app will only display integer values, which is pretty useless for 'kPa' or 'inHg'.
        // It is recommended that 'hPa', 'mbar', or 'mmHg' be used instead as they can reasonably be rounded.
		map = [ name: 'atmosphericPressure', value: (int) pressureval.round(0), unit: pressureunit ]
	} 
    else if (cluster == "0000" && attrid == "0005")  {
		// Not interested.
	}
    
	return map
}

Map battery( raw )
{
	// Experience shows that a new battery in an Aqara sensor reads about 3.2V, and they need
	// changing when you get down to about 2.7V. It really isn't worth messing around with 
	// preferences to fine tune this.

	def rawvolts = raw / 1000
    
	logger( 'battery', 'debug', "$rawvolts V" )
    
	def minvolts = 2.7
	def maxvolts = 3.2
	def percent = Math.min( 100, Math.round( 100.0 * ( rawvolts - minvolts ) / ( maxvolts - minvolts ) ) )

	// If checkInterval is still 24 hours, set a shorter one.
    if ( device.currentValue( 'checkInterval' ) == 86400 )
    {
        // Set checkInterval to two hours and ten minutes now a battery value has arrived.
    	sendEvent( name: 'checkInterval', value: 7800, data: [ protocol: 'zigbee', hubHardwareId: device.hub.hardwareID ] )
                       
        logger( 'battery', 'debug', 'checkInterval 7800 seconds' )
	}
 
	return [ name: 'battery', value: percent, unit: '%', isStateChange: true ]
}