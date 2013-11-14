package com.taodian.click;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taodian.api.TaodianApi;
import com.taodian.emop.Settings;
import com.taodian.emop.http.HTTPResult;
import com.taodian.emop.utils.CacheApi;
import com.taodian.emop.utils.SimpleCacheApi;

/**
 * 短网址的服务类。
 * 
 * @author deonwu
 */
public class ShortUrlService {
	private Log log = LogFactory.getLog("click.shorturl.service");
	private Log accesslog = null;

	private TaodianApi api = null;
	private CacheApi cache = new SimpleCacheApi();
	private static ShortUrlService ins = null;
	
	private long nextUID = 0;
	private int MODE = 10000;
	protected ThreadPoolExecutor workerPool = null;
	
	public static synchronized ShortUrlService getInstance(){
		if(ins == null){
			ins = new ShortUrlService();
			ins.start();
		}
		return ins;
	}
	
	/**
	 * 开始短网址服务，主要是初始化淘客API的连接信息。
	 */
	protected void start(){
		String appKey = Settings.getString(Settings.TAODIAN_APPID, null); // System.getProperty("");
		String appSecret = Settings.getString(Settings.TAODIAN_APPSECRET, null);
		String appRoute = Settings.getString(Settings.TAODIAN_APPROUTE, "http://api.zaol.cn/api/route");
		
		if(appKey != null && appSecret != null){
			api = new TaodianApi(appKey, appSecret, appRoute);		
		}else {
			log.info("The taodian.api_id and taodian.api_secret Java properties are required.");
			System.exit(255);
		}
		
		int writeLogThread = Settings.getInt(Settings.WRITE_LOG_THREAD_COUNT, 10);
		int queueSize = Settings.getInt(Settings.WRITE_LOG_QUEUE_SIZE, 1024);
		
		log.debug("start log write thread pool, core size:" + writeLogThread + ", queue size:");
		workerPool = new ThreadPoolExecutor(
				writeLogThread,
				writeLogThread * 2,
				10, 
				TimeUnit.SECONDS, 
				new LinkedBlockingDeque<Runnable>(queueSize)
				);
		
		if(Settings.getString(Settings.WRITE_ACCESS_LOG, "y").equals("y")){
			accesslog = LogFactory.getLog("click.accesslog");
		}
	}
	
	public ShortUrlModel getShortUrlInfo(String shortKey, boolean noCache){
		Object tmp = cache.get(shortKey, true);
		
		if(tmp == null || noCache){
			Map<String, Object> param = new HashMap<String, Object>();
			param.put("short_key", shortKey);
			param.put("auto_mobile", "y");
			
			HTTPResult r = api.call("tool_convert_long_url", param);
			
			ShortUrlModel m = new ShortUrlModel();
			if(r.isOK){
				m.shortKey = shortKey;
				m.longUrl = r.getString("data.long_url");
				m.mobileLongUrl = r.getString("data.mobile_long_url");
				cache.set(shortKey, m, 5 * 60);
				
				tmp = m;
			}
		}
		
		if(tmp != null && tmp instanceof ShortUrlModel){
			return (ShortUrlModel)tmp;
		}
		return null;
	}
	
	public void writeClickLog(final ShortUrlModel model){
		workerPool.execute(new Runnable(){
			public void run(){
				writeClickLogWithApi(model);
			}
		});
	}
	
	private void writeClickLogWithApi(ShortUrlModel model){
		Map<String, Object> param = new HashMap<String, Object>();
		param.put("short_key", model.shortKey);
		param.put("uid", model.uid);
		param.put("ip_addr", model.ip);
		param.put("agent", model.agent);
		param.put("refer", model.refer);
		
		HTTPResult r = api.call("tool_short_url_click", param);
		
		ShortUrlModel m = new ShortUrlModel();
		String msg = String.format("%s %s %s [%s] %s", model.shortKey, model.uid, model.ip,
				model.agent, model.refer);
		if(accesslog != null){
			accesslog.debug(msg);
		}else {
			log.debug(msg);
		}
		if(!r.isOK){
			log.warn("click log write error, short:" + model.clickId + ", msg:" + r.errorMsg);
		}
	}
	
	public long newUserId(){
		nextUID = (nextUID + 1) % MODE;
		
		return (System.currentTimeMillis() % (MODE * 1000)) * MODE + nextUID; 
	}
	

}