package com.thomsonreuters.upa.valueadd.reactor;


import java.util.List;
import java.util.Objects;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thomsonreuters.upa.codec.Buffer;
import com.thomsonreuters.upa.codec.CodecFactory;


class RestHandler implements FutureCallback<HttpResponse> {
	
    private RestResultClosure _resultClosure;
    private RestReactor _restReactor;
    private RestEvent _event;
    private RestResponse _response;
    private int _id = 0;
	private Logger loggerClient = null;
	private String _contentString = null;
 
    // variables only for redirection invocation of appropriate RestReactor method
	RestAuthOptions _authOptions = null; 
	RestConnectOptions _restConnectOptions = null; 
	ReactorAuthTokenInfo _authTokenInfo = null;
	ReactorErrorInfo _errorInfo = null;
	RestRequest _request = null;
	List<ReactorServiceEndpointInfo> _reactorServiceEndpointInfoList = null; 
     
    RestHandler(RestReactor restReactor, RestResultClosure resultClosure)
	{
         _resultClosure = resultClosure;
         _restReactor = restReactor;
         _event = new RestEvent(RestEventTypes.COMPLETED, resultClosure);
         _id = 0;
         loggerClient = LoggerFactory.getLogger(RestReactor.class);
	}
    
    // constructor invoked from submitAuthRequest()
    RestHandler(RestReactor restReactor,
    		RestAuthOptions authOptions, 
    		RestConnectOptions restConnectOptions, 
   		 	ReactorAuthTokenInfo authTokenInfo, 
   		 	ReactorErrorInfo errorInfo
    		)
	{
         _resultClosure = restConnectOptions.restResultClosure();
         _restReactor = restReactor;
         _event = new RestEvent(RestEventTypes.COMPLETED, _resultClosure);
         
 		_authOptions = authOptions;
 		_restConnectOptions = restConnectOptions; 
		_authTokenInfo = authTokenInfo;
		_errorInfo = errorInfo;
         
         _id = RestReactor.AUTH_HANDLER;
         
         loggerClient = LoggerFactory.getLogger(RestReactor.class);
	}
    
    // constructor invoked from submitRequestForServiceDiscovery()
    RestHandler(RestReactor restReactor, 
    		RestRequest request, 
    		RestConnectOptions restConnectOptions, 
    		ReactorAuthTokenInfo authTokenInfo,
    		List<ReactorServiceEndpointInfo> reactorServiceEndpointInfoList, 
    		ReactorErrorInfo errorInfo
    		)
	{
         _resultClosure = restConnectOptions.restResultClosure();
         _restReactor = restReactor;
         _event = new RestEvent(RestEventTypes.COMPLETED, _resultClosure);
         
  		_request = request;
  		_restConnectOptions = restConnectOptions; 
 		_authTokenInfo = authTokenInfo;
 		_reactorServiceEndpointInfoList = reactorServiceEndpointInfoList;
 		_errorInfo = errorInfo;
         
         _id = RestReactor.DISCOVERY_HANDLER;
         
         loggerClient = LoggerFactory.getLogger(RestReactor.class);
	}

	@Override
	public void completed(HttpResponse response)
	{
		_event.clear();
		_event.eventType(RestEventTypes.COMPLETED);
		_event.resultClosure(_resultClosure);
		_response = new RestResponse();
		
  		// Extracting content string for further logging and processing if needed.
  		HttpEntity entityFromResponse = response.getEntity();
  		String contentString = _contentString;
  		Exception extractingContentException = null;
  		
  		/* Checks whether the content string is available */
  		if(Objects.isNull(contentString))
  		{
	  		try {
	  			contentString =  EntityUtils.toString(entityFromResponse);
	  		} catch (Exception e) {
	  			extractingContentException = e;
	  		}
  		}

  		if (loggerClient.isTraceEnabled()) {
			loggerClient.trace(_restReactor.prepareResponseString(response, 
					contentString, extractingContentException));
		}
  		
		int statusCode = response.getStatusLine().getStatusCode();
		switch (statusCode) {
		case HttpStatus.SC_OK:
			RestReactor.convertResponse(_restReactor, response, _response, _event.errorInfo(),
					contentString, extractingContentException);
			if ((_id == RestReactor.AUTH_HANDLER) && (_restConnectOptions.authRedirect())) {
				clearAuthRedirectParameters();
			}
			if ((_id == RestReactor.DISCOVERY_HANDLER) && (_restConnectOptions.discoveryRedirect())) {
				clearDiscoveryRedirectParameters();
			}
			break;
		case HttpStatus.SC_MOVED_PERMANENTLY:
		case HttpStatus.SC_MOVED_TEMPORARILY:
		case HttpStatus.SC_TEMPORARY_REDIRECT:
		case 308:                // HttpStatus.SC_PERMANENT_REDIRECT:
			Header location = response.getFirstHeader("Location");
			if (location != null) {
				String newHost = location.getValue();
				if ((newHost != null) && (!newHost.isEmpty())) {
					if (_id == RestReactor.AUTH_HANDLER) {
						if (!_restConnectOptions.authRedirect()) {
							_restReactor.submitAuthRequest(_authOptions, 
									_restConnectOptions, _authTokenInfo, _errorInfo, 
									true, newHost);
							
							if ((statusCode == HttpStatus.SC_MOVED_PERMANENTLY) || (statusCode == 308)) {
	   	                    	Buffer newUrl = CodecFactory.createBuffer();
   	   	                    	newUrl.data(location.getValue());
   	   	                    	_restConnectOptions.reactorOptions().tokenServiceURL(newUrl);
							}
							
							return;
						} else {
							clearAuthRedirectParameters();
						}
					} else if (_id == RestReactor.DISCOVERY_HANDLER) {
						if (!_restConnectOptions.discoveryRedirect()) {
							_restReactor.submitRequestForServiceDiscovery(_request, 
									_restConnectOptions, _authTokenInfo, 
									_reactorServiceEndpointInfoList, _errorInfo,
									true, newHost);
							
							if ((statusCode == HttpStatus.SC_MOVED_PERMANENTLY) || (statusCode == 308)) {
	   	                    	Buffer newUrl = CodecFactory.createBuffer();
   	   	                    	newUrl.data(location.getValue());
   	   	                    	_restConnectOptions.reactorOptions().serviceDiscoveryURL(newUrl);
							}
						} else {
							clearDiscoveryRedirectParameters();
						}
						
						return;
					}
				}
			}
			RestReactor.convertResponse(_restReactor, response, _response, _event.errorInfo(),
					contentString, extractingContentException);
			_event.eventType(RestEventTypes.FAILED);
			if ((_id == RestReactor.AUTH_HANDLER) && (_restConnectOptions.authRedirect())) {
				clearAuthRedirectParameters();
			}
			if ((_id == RestReactor.DISCOVERY_HANDLER) && (_restConnectOptions.discoveryRedirect())) {
				clearDiscoveryRedirectParameters();
			}
			break;
		case HttpStatus.SC_FORBIDDEN:
		case 451: //  Unavailable For Legal Reasons
			
			RestReactor.convertResponse(_restReactor, response, _response, _event.errorInfo(),
					contentString, extractingContentException);
			_event.eventType(RestEventTypes.STOPPED);
			if ((_id == RestReactor.AUTH_HANDLER) && (_restConnectOptions.authRedirect())) {
				clearAuthRedirectParameters();
			}
			if ((_id == RestReactor.DISCOVERY_HANDLER) && (_restConnectOptions.discoveryRedirect())) {
				clearDiscoveryRedirectParameters();
			}
		
			break;

		default:
			RestReactor.convertResponse(_restReactor, response, _response, _event.errorInfo(),
					contentString, extractingContentException);
			_event.eventType(RestEventTypes.FAILED);
			if ((_id == RestReactor.AUTH_HANDLER) && (_restConnectOptions.authRedirect())) {
				clearAuthRedirectParameters();
			}
			if ((_id == RestReactor.DISCOVERY_HANDLER) && (_restConnectOptions.discoveryRedirect())) {
				clearDiscoveryRedirectParameters();
			}
		}
			
		RestReactor.processResponse(_restReactor, _response, _event);
	}
	
	public void clearAuthRedirectParameters() {
		_restConnectOptions.authRedirect(false);
		_restConnectOptions.authRedirectLocation(null);
	}

	public void clearDiscoveryRedirectParameters() {
		_restConnectOptions.discoveryRedirect(false);
		_restConnectOptions.discoveryRedirectLocation(null);
	}
	

	@Override
	public void failed(Exception ex)
	{
		_event.clear();
		_event.eventType(RestEventTypes.FAILED);
		_event.resultClosure(_resultClosure);
		_resultClosure.restCallback().RestErrorCallback(_event, ex.getLocalizedMessage());
		
		if (loggerClient.isTraceEnabled()) {
			loggerClient.trace("Failed to send HTTP  request, exception = " + 
					RestReactor.getExceptionCause(ex) + "\n");
		}
	}

	@Override
	public void cancelled()
	{
		_event.clear();
		_event.eventType(RestEventTypes.CANCELLED);
		_event.resultClosure(_resultClosure);
		RestReactor.populateErrorInfo(_event.errorInfo(),
                ReactorReturnCodes.FAILURE,
                "RestHandler.cancelled", "Cancelled REST request.");
		
		_resultClosure.restCallback().RestErrorCallback(_event,  _event.errorInfo().error().text());
	}
	
    /**
     * Returns a String representation of this object.
     * 
     * @return a String representation of this object
     */
    public String toString()
    {
        return (_restReactor == null ? "_RestReactor null" : _restReactor)
                + ", "
                + (_resultClosure == null ? "_resultClosure null" : _resultClosure)
                + ", "
                + (_event == null ? "_event null" : _event.toString())
                + ", "
                + (_response == null ? "_response null" : _response.toString());
    }
    
    /* Set the content string from the HTTP entity */
    void contentString(String contentString)
    {
    	_contentString = contentString;
    }
}
