package com.ksyun.ks3.service.request;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.ksyun.ks3.MD5DigestCalculatingInputStream;
import com.ksyun.ks3.RepeatableFileInputStream;
import com.ksyun.ks3.RepeatableInputStream;
import com.ksyun.ks3.config.Constants;
import com.ksyun.ks3.dto.AccessControlList;
import com.ksyun.ks3.dto.CannedAccessControlList;
import com.ksyun.ks3.dto.Grant;
import com.ksyun.ks3.dto.ObjectMetadata;
import com.ksyun.ks3.dto.Permission;
import com.ksyun.ks3.exception.Ks3ClientException;
import com.ksyun.ks3.http.HttpHeaders;
import com.ksyun.ks3.http.HttpMethod;
import com.ksyun.ks3.http.Mimetypes;
import com.ksyun.ks3.service.request.support.MD5CalculateAble;
import com.ksyun.ks3.utils.HttpUtils;
import com.ksyun.ks3.utils.Md5Utils;
import com.ksyun.ks3.utils.StringUtils;

/**
 * @author lijunwei[lijunwei@kingsoft.com]  
 * 
 * @date 2014年10月22日 上午10:28:06
 * 
 * @description 上传文件的请求
 *              <p>
 *              提供通过文件来上传，请使用PutObjectRequest(String bucketname, String key,
 *              File file)这个构造函数
 *              </p>
 *              <p>
 *              提供通过流来上传，请使用PutObjectRequest(String bucketname, String
 *              key,InputStream inputStream, ObjectMetadata
 *              metadata)这个构造函数，使用时请尽量在metadata中提供content
 *              -length,否则有可能导致jvm内存溢出。可以再metadata中指定contentMD5
 *              </p>
 **/
public class PutObjectRequest extends Ks3WebServiceRequest implements
		MD5CalculateAble {
	/**
	 * 要上传的文件
	 */
	private File file;
	/**
	 * 将要上传的object的元数据
	 */
	private ObjectMetadata objectMeta = new ObjectMetadata();
	/**
	 * 设置新的object的acl
	 */
	private CannedAccessControlList cannedAcl;
	/**
	 * 设置新的object的acl
	 */
	private AccessControlList acl = new AccessControlList();
	private String redirectLocation;

	/**
	 * 
	 * @param bucketname
	 * @param key
	 * @param file
	 *            要上传的文件
	 */
	public PutObjectRequest(String bucketname, String key, File file) {
		this.setBucketname(bucketname);
		this.setObjectkey(key);
		this.setFile(file);
	}

	/**
	 * 
	 * @param bucketname
	 * @param key
	 * @param inputStream
	 * @param metadata
	 *            请尽量提供content-length,否则可能会导致jvm内存溢出
	 */
	public PutObjectRequest(String bucketname, String key,
			InputStream inputStream, ObjectMetadata metadata) {
		this.setBucketname(bucketname);
		this.setObjectkey(key);
		this.setObjectMeta(metadata);
		this.setRequestBody(new RepeatableInputStream(inputStream,
				Constants.DEFAULT_STREAM_BUFFER_SIZE));
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void configHttpRequest() {
		this.setContentType("binary/octet-stream");
		/**
		 * 设置request body meta
		 */
		if (file != null) {
			try {
				this.setRequestBody(new RepeatableFileInputStream(file));
			} catch (FileNotFoundException e) {
				throw new Ks3ClientException("file :" + file.getName()
						+ " not found");
			}
			if (StringUtils.isBlank(objectMeta.getContentType()))
				objectMeta.setContentType(Mimetypes.getInstance().getMimetype(
						file));
			long length = file.length();
			objectMeta.setContentLength(length);
			try {
				String contentMd5_b64 = Md5Utils.md5AsBase64(file);
				this.objectMeta.setContentMD5(contentMd5_b64);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				throw new Ks3ClientException("file :" + file.getName()
						+ " not found");
			} catch (IOException e) {
				e.printStackTrace();
				throw new Ks3ClientException("calculate file md5 error (" + e
						+ ")", e);
			}
		}
		if (this.objectMeta != null) {
			if (!StringUtils.isBlank(this.objectMeta.getContentType()))
				this.addHeader(HttpHeaders.ContentType,
						this.objectMeta.getContentType());
			if (!StringUtils.isBlank(this.objectMeta.getCacheControl()))
				this.addHeader(HttpHeaders.CacheControl,
						this.objectMeta.getCacheControl());
			if (!StringUtils.isBlank(this.objectMeta.getContentDisposition()))
				this.addHeader(HttpHeaders.ContentDisposition,
						this.objectMeta.getContentDisposition());
			if (!StringUtils.isBlank(this.objectMeta.getContentEncoding()))
				this.addHeader(HttpHeaders.ContentEncoding,
						this.objectMeta.getContentEncoding());
			if (this.objectMeta.getContentLength() > 0)
				this.addHeader(HttpHeaders.ContentLength,
						String.valueOf(this.objectMeta.getContentLength()));
			if (this.objectMeta.getHttpExpiresDate() != null)
				this.addHeader(HttpHeaders.Expires, this.objectMeta
						.getHttpExpiresDate().toGMTString());
			if (this.objectMeta.getContentMD5() != null)
				this.addHeader(HttpHeaders.ContentMD5,
						this.objectMeta.getContentMD5());
			// 添加user meta
			for (Entry<String, String> entry : this.objectMeta.getAllUserMeta()
					.entrySet()) {
				if (entry.getKey().startsWith(Constants.KS3_USER_META_PREFIX))
					this.addHeader(entry.getKey(), entry.getValue());
			}
		}
		// acl
		if (this.cannedAcl != null) {
			this.addHeader(HttpHeaders.CannedAcl.toString(),
					cannedAcl.toString());
		}
		if (this.acl != null) {
			this.getHeader().putAll(HttpUtils.convertAcl2Headers(acl));
		}
		if (this.redirectLocation != null) {
			this.addHeader(HttpHeaders.XKssWebsiteRedirectLocation,
					this.redirectLocation);
		}
		this.setHttpMethod(HttpMethod.PUT);
	}

	@Override
	protected void validateParams() throws IllegalArgumentException {
		if (StringUtils.isBlank(this.getBucketname()))
			throw new IllegalArgumentException("bucket name can not be null");
		if (StringUtils.isBlank(this.getObjectkey()))
			throw new IllegalArgumentException("object key can not be null");
		if (file == null && this.getRequestBody() == null)
			throw new IllegalArgumentException("upload object can not be null");
		if (this.redirectLocation != null) {
			if (!this.redirectLocation.startsWith("/")
					&& !this.redirectLocation.startsWith("http://")
					&& !this.redirectLocation.startsWith("https://"))
				throw new IllegalArgumentException(
						"redirectLocation should start with / http:// or https://");
		}
		if(file!=null){
			if(file.length()>Constants.maxSingleUpload)
				throw new IllegalArgumentException("upload file too large,max bytes:"+Constants.maxSingleUpload);
		}else{
			if(this.objectMeta!=null&&this.objectMeta.getContentLength()>Constants.maxSingleUpload)
				throw new IllegalArgumentException("Content-length you provided is too large,max:"+Constants.maxSingleUpload);
		}
	}

	public File getFile() {
		return file;
	}

	private void setFile(File file) {
		this.file = file;
	}

	public ObjectMetadata getObjectMeta() {
		return objectMeta;
	}

	public void setObjectMeta(ObjectMetadata objectMeta) {
		this.objectMeta = objectMeta;
	}

	public CannedAccessControlList getCannedAcl() {
		return cannedAcl;
	}

	public void setCannedAcl(CannedAccessControlList cannedAcl) {
		this.cannedAcl = cannedAcl;
	}

	public AccessControlList getAcl() {
		return acl;
	}

	public void setAcl(AccessControlList acl) {
		this.acl = acl;
	}

	public String getRedirectLocation() {
		return redirectLocation;
	}

	public void setRedirectLocation(String redirectLocation) {
		this.redirectLocation = redirectLocation;
	}

	public String getMd5() {
		if (!StringUtils.isBlank(this.getContentMD5()))
			return this.getContentMD5();
		else
			return com.ksyun.ks3.utils.Base64
					.encodeAsString(((MD5DigestCalculatingInputStream) super
							.getRequestBody()).getMd5Digest());
	}

}
