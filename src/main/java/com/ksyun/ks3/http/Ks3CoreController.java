package com.ksyun.ks3.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

import com.ksyun.ks3.MD5DigestCalculatingInputStream;
import com.ksyun.ks3.config.ClientConfig;
import com.ksyun.ks3.config.Constants;
import com.ksyun.ks3.dto.Authorization;
import com.ksyun.ks3.dto.GetObjectResult;
import com.ksyun.ks3.dto.Ks3Result;
import com.ksyun.ks3.exception.Ks3ClientException;
import com.ksyun.ks3.exception.Ks3ServiceException;
import com.ksyun.ks3.exception.client.ClientHttpException;
import com.ksyun.ks3.exception.client.ClientIllegalArgumentException;
import com.ksyun.ks3.exception.client.ClientInvalidDigestException;
import com.ksyun.ks3.service.request.Ks3WebServiceRequest;
import com.ksyun.ks3.service.request.SSECustomerKeyRequest;
import com.ksyun.ks3.service.request.UploadPartRequest;
import com.ksyun.ks3.service.response.Ks3WebServiceResponse;
import com.ksyun.ks3.signer.Signer;
import com.ksyun.ks3.utils.AuthUtils;
import com.ksyun.ks3.utils.Base64;
import com.ksyun.ks3.utils.Converter;
import com.ksyun.ks3.utils.HttpUtils;
import com.ksyun.ks3.utils.StringUtils;
import com.ksyun.ks3.utils.Timer;

/**
 * @author lijunwei[lijunwei@kingsoft.com]  
 * 
 * @date 2014年10月14日 下午6:59:38
 * 
 * @description ks3 sdk Ks3WebServiceRequest执行，K3WebServiceResponse解析核心控制器
 **/
public class Ks3CoreController {
	private static final Log log = LogFactory.getLog(Ks3CoreController.class);
	private HttpClientFactory factory = new HttpClientFactory();
	private HttpClient client;

	public <X extends Ks3WebServiceResponse<Y>, Y> Y execute(
			Authorization auth, Ks3WebServiceRequest request, Class<X> clazz) {
		if (request == null)
			throw new Ks3ClientException("request can not be null");
		log.debug("Ks3WebServiceRequest:" + request.getClass()
				+ ";Ks3WebServiceResponse:" + clazz);
		Y result = null;
		try {
			if (auth == null || StringUtils.isBlank(auth.getAccessKeyId())
					|| StringUtils.isBlank(auth.getAccessKeySecret()))
				throw new Ks3ClientException(
						"AccessKeyId or AccessKeySecret can't be null");
			if (request == null || clazz == null)
				throw new IllegalArgumentException();
			result = doExecute(auth, request, clazz);
			return result;
		} catch (RuntimeException e) {
			if (e instanceof Ks3ClientException) {

			} else {
				if (e instanceof IllegalArgumentException) {
					ClientIllegalArgumentException ce = new ClientIllegalArgumentException(
							e.getMessage());
					ce.setStackTrace(e.getStackTrace());
					e = ce;
				} else
					e = new Ks3ClientException(e);
			}
			log.error(e);
			throw e;
		} catch (IOException e) {
			log.error(e);
			throw new Ks3ClientException(e);
		} finally {
		}
	}

	private <X extends Ks3WebServiceResponse<Y>, Y> Y doExecute(
			Authorization auth, Ks3WebServiceRequest request, Class<X> clazz)
			throws IllegalStateException, IOException {
		Timer.start();
		this.client = this.factory.createHttpClient();
		HttpResponse response = null;
		Request req = new Request();
		HttpRequestBase httpRequest = HttpRequestBuilder.build(request,req, auth);
		try {
			log.debug(httpRequest.getRequestLine());
			response = client.execute(httpRequest);
			log.debug(response.getStatusLine());
			if (response.getStatusLine().getStatusCode() == 307
					&& response.containsHeader("Location")) {
				String location = response.getHeaders("Location")[0].getValue();
				// TODO 这个只是为了兼容当前api
				if (location.startsWith("http")) {
					log.debug("returned 307,retry request to " + location);
					restRequest(httpRequest);
					httpRequest.setURI(new URI(location));
					response = client.execute(httpRequest);
				}
			}
			closeInputStream(httpRequest);
			log.debug("finished send request to ks3 service and recive response from the service : "
					+ Timer.end());
		} catch (Exception e) {
			if(e instanceof Ks3ClientException)
				throw (Ks3ClientException)e;
			throw new ClientHttpException(e);
		}
		Ks3WebServiceResponse<Y> ksResponse = null;
		try {
			ksResponse = clazz.newInstance();
		} catch (InstantiationException e) {
			// 正常情况不会抛出
			throw new Ks3ClientException("to instantiate " + clazz
					+ " has occured an exception:(" + e + ")", e);
		} catch (IllegalAccessException e) {
			// 正常情况不会抛出
			throw new Ks3ClientException("to instantiate " + clazz
					+ " has occured an exception:(" + e + ")", e);
		}
		String requestId = response.getFirstHeader(HttpHeaders.RequestId
				.toString()) == null ? "" : response
				.getFirstHeader(HttpHeaders.RequestId.toString())
				.getValue();
		if (!success(response, ksResponse)) {
			throw new Ks3ServiceException(response, StringUtils.join(
					ksResponse.expectedStatus(), ",")
					+ "("
					+ Ks3WebServiceResponse.allStatueCode
					+ " is all statue codes)")
					.convert(requestId);
		}
		Y result = ksResponse.handleResponse(httpRequest, response);
		Map<String, String> ret = skipMD5Check(response, req);
		if (ret.size() == 2) {
			log.debug("returned etag is:" + ret.get("ETag"));
			if (!ret.get("ETag").equals(Converter.MD52ETag(ret.get("MD5")))) {
				throw new ClientInvalidDigestException(
						"Unable to verify integrity of data upload.  "
								+ "Client calculated content hash didn't match hash calculated by KS3.  "
								+ "You may need to delete the data stored in KS3.");
			}
		} else {
			log.debug("client MD5 check skipped");
		}
		if(result instanceof Ks3Result){
			((Ks3Result)result).setRequestId(requestId);
		}
		log.debug("finished handle response : " + Timer.end());
		return result;
	}

	/**
	 * 查看返回的状态码是否为期望的状态码
	 * 
	 * @param response
	 *            {@link HttpResponse}
	 * @param kscResponse
	 *            {@link Ks3WebServiceResponse}
	 * @return 是 ： true 否：false
	 */
	private boolean success(HttpResponse response,
			Ks3WebServiceResponse<?> kscResponse) {
		int num = kscResponse.expectedStatus().length;
		int code = response.getStatusLine().getStatusCode();
		for (int i = 0; i < num; i++) {
			if (kscResponse.expectedStatus()[i] == Ks3WebServiceResponse.allStatueCode)
				return true;
			if (code == kscResponse.expectedStatus()[i])
				return true;
		}
		return false;
	}

	private Map<String, String> skipMD5Check(HttpResponse rep, Request req) {
		Map<String, String> map = new HashMap<String, String>();
		InputStream content = req.getContent();
		if (content == null
				|| !(content instanceof MD5DigestCalculatingInputStream))
			return map;
		String clientmd5 = Base64
				.encodeAsString(((MD5DigestCalculatingInputStream) content)
						.getMd5Digest());
		Header etagHeader = rep.getFirstHeader(HttpHeaders.ETag.toString());
		if (etagHeader == null)
			return map;
		String etag = etagHeader.getValue();
		if (StringUtils.isBlank(etag) || StringUtils.isBlank(clientmd5))
			return map;
		map.put("ETag", etag);
		map.put("MD5", clientmd5);
		return map;
	}
	private void closeInputStream(HttpRequest req) throws IllegalStateException, IOException{
		HttpEntity entity = null;
		if(req instanceof HttpPut){
			entity = ((HttpPut)req).getEntity();
		}else if(req instanceof HttpPost){
			entity = ((HttpPost)req).getEntity();
		}
		if(entity != null){
			InputStream input = entity.getContent();
			if(input!=null)
				input.close();
		}
	}
	private void restRequest(HttpRequest req) throws IllegalStateException, IOException{
		HttpEntity entity = null;
		if(req instanceof HttpPut){
			entity = ((HttpPut)req).getEntity();
		}else if(req instanceof HttpPost){
			entity = ((HttpPost)req).getEntity();
		}
		if(entity != null){
			InputStream input = entity.getContent();
			if(input!=null){
				if(input.markSupported()){
					input.reset();
					input.mark(-1);
				}
			}
		}
	}
}
