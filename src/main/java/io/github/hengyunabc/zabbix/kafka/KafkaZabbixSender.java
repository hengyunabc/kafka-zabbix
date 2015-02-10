package io.github.hengyunabc.zabbix.kafka;

import io.github.hengyunabc.metrics.MessageListener;
import io.github.hengyunabc.metrics.MetricsKafkaConsumer;
import io.github.hengyunabc.zabbix.api.Request;
import io.github.hengyunabc.zabbix.api.RequestBuilder;
import io.github.hengyunabc.zabbix.api.ZabbixApi;
import io.github.hengyunabc.zabbix.sender.DataObject;
import io.github.hengyunabc.zabbix.sender.SenderResult;
import io.github.hengyunabc.zabbix.sender.ZabbixSender;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class KafkaZabbixSender {
	private static final Logger logger = LoggerFactory
			.getLogger(KafkaZabbixSender.class);

	MetricsKafkaConsumer consumer;
	ZabbixSender zabbixSender;
	ZabbixApi zabbixApi;

	boolean bCreateNotExistHostGroup = true;
	boolean bCreateNotExistHost = true;
	String hostGroup = "metrics-group"; // default host group
	boolean bCreateNotExistItem = true;

	String zookeeper;
	String topic;
	String group;

	// name, hostGroupId
	Map<String, String> hostGroupCache = new ConcurrentHashMap<String, String>();
	// name, hostId
	Map<String, String> hostCache = new ConcurrentHashMap<String, String>();
	// name, itemId
	Map<String, String> itemCache = new ConcurrentHashMap<String, String>();

	void checkHostGroup(String hostGroup) {
		if (hostGroupCache.get(hostGroup) == null) {
			JSONObject filter = new JSONObject();
			filter.put("name", new String[] { hostGroup });
			Request getRequest = RequestBuilder.newBuilder()
					.method("hostgroup.get").paramEntry("filter", filter)
					.build();
			JSONObject getResponse = zabbixApi.call(getRequest);
			JSONArray result = getResponse.getJSONArray("result");
			if (!result.isEmpty()) { // host group exists.
				String groupid = result.getJSONObject(0).getString("groupid");
				hostGroupCache.put(hostGroup, groupid);
			} else {// host group not exists, create it.
				Request createRequest = RequestBuilder.newBuilder()
						.method("hostgroup.create")
						.paramEntry("name", hostGroup).build();
				JSONObject createResponse = zabbixApi.call(createRequest);
				String hostGroupId = createResponse.getJSONObject("result")
						.getJSONArray("groupids").getString(0);
				hostGroupCache.put(hostGroup, hostGroupId);
			}
		}
	}

	void checkHost(String host, String ip) {
		if (hostCache.get(host) == null) {
			JSONObject filter = new JSONObject();
			filter.put("host", new String[] { host });
			Request getRequest = RequestBuilder.newBuilder().method("host.get")
					.paramEntry("filter", filter).build();
			JSONObject getResponse = zabbixApi.call(getRequest);
			JSONArray result = getResponse.getJSONArray("result");
			if (!result.isEmpty()) { // host exists.
				String hostid = result.getJSONObject(0).getString("hostid");
				hostCache.put(host, hostid);
			} else {// host not exists, create it.
				JSONArray groups = new JSONArray();
				JSONObject group = new JSONObject();
				group.put("groupid", hostGroupCache.get(hostGroup));
				groups.add(group);

				// "interfaces": [
				// {
				// "type": 1,
				// "main": 1,
				// "useip": 1,
				// "ip": "192.168.3.1",
				// "dns": "",
				// "port": "10050"
				// }
				// ],

				JSONObject interface1 = new JSONObject();
				interface1.put("type", 1);
				interface1.put("main", 1);
				interface1.put("useip", 1);
				interface1.put("ip", ip);
				interface1.put("dns", "");
				interface1.put("port", "10051");

				Request request = RequestBuilder.newBuilder()
						.method("host.create").paramEntry("host", host)
						.paramEntry("groups", groups)
						.paramEntry("interfaces", new Object[] { interface1 })
						.build();
				JSONObject response = zabbixApi.call(request);
				String hostId = response.getJSONObject("result")
						.getJSONArray("hostids").getString(0);
				hostCache.put(host, hostId);
			}
		}
	}

	private String itemCacheKey(String host, String item) {
		return host + ":" + item;
	}

	void checkItem(String host, String item) {

		if (itemCache.get(itemCacheKey(host, item)) == null) {
			JSONObject search = new JSONObject();
			search.put("key_", item);
			Request getRequest = RequestBuilder.newBuilder().method("item.get")
					.paramEntry("hostids", hostCache.get(host))
					.paramEntry("search", search).build();
			JSONObject getResponse = zabbixApi.call(getRequest);
			JSONArray result = getResponse.getJSONArray("result");
			if (result.isEmpty()) {
				// create item
				int type = 2; // trapper
				int value_type = 0; // float
				int delay = 30;
				Request request = RequestBuilder.newBuilder()
						.method("item.create").paramEntry("name", item)
						.paramEntry("key_", item)
						.paramEntry("hostid", hostCache.get(host))
						.paramEntry("type", type)
						.paramEntry("value_type", value_type)
						.paramEntry("delay", delay).build();

				JSONObject response = zabbixApi.call(request);
				String itemId = response.getJSONObject("result")
						.getJSONArray("itemids").getString(0);
				itemCache.put(itemCacheKey(host, item), itemId);
			} else {
				// put into cache
				itemCache.put(itemCacheKey(host, item), result.getJSONObject(0)
						.getString("itemid"));
			}
		}
	}

	public void init() {
		consumer = new MetricsKafkaConsumer();
		consumer.setZookeeper(zookeeper);
		consumer.setTopic(topic);
		consumer.setGroup(group);
		consumer.setMessageListener(new MessageListener() {

			@Override
			public void onMessage(JSONObject message) {
				System.err.println(message);
				String hostName = message.getString("hostName");
				String ip = message.getString("ip");
				if (bCreateNotExistHostGroup) {
					checkHostGroup(hostGroup);
				}
				if (bCreateNotExistHost) {
					checkHost(hostName, ip);
				}

				long clock = message.getLongValue("clock");

				List<DataObject> dataObjectList = new LinkedList();

				JSONObject meters = message.getJSONObject("meters");
				for (Entry<String, Object> entry : meters.entrySet()) {
					DataObject dataObject = DataObject.builder().host(hostName)
							.key(entry.getKey())
							.value(entry.getValue().toString()).clock(clock)
							.build();
					dataObjectList.add(dataObject);
				}

				JSONObject gauges = message.getJSONObject("gauges");
				for (Entry<String, Object> entry : gauges.entrySet()) {
					DataObject dataObject = DataObject.builder().host(hostName)
							.key(entry.getKey())
							.value(entry.getValue().toString()).clock(clock)
							.build();
					dataObjectList.add(dataObject);
				}

				JSONObject couters = message.getJSONObject("couters");
				for (Entry<String, Object> entry : couters.entrySet()) {
					DataObject dataObject = DataObject.builder().host(hostName)
							.key(entry.getKey())
							.value(entry.getValue().toString()).clock(clock)
							.build();
					dataObjectList.add(dataObject);
				}

				JSONObject histograms = message.getJSONObject("histograms");
				for (Entry<String, Object> entry : histograms.entrySet()) {

					for (Entry<String, Object> detailEntry : ((JSONObject) entry
							.getValue()).entrySet()) {
						DataObject dataObject = DataObject
								.builder()
								.host(hostName)
								.key(entry.getKey() + "."
										+ detailEntry.getKey())
								.value(detailEntry.getValue().toString())
								.clock(clock).build();
						dataObjectList.add(dataObject);
					}
				}

				JSONObject timers = message.getJSONObject("timers");
				for (Entry<String, Object> entry : timers.entrySet()) {

					for (Entry<String, Object> detailEntry : ((JSONObject) entry
							.getValue()).entrySet()) {
						DataObject dataObject = DataObject
								.builder()
								.host(hostName)
								.key(entry.getKey() + "."
										+ detailEntry.getKey())
								.value(detailEntry.getValue().toString())
								.clock(clock).build();
						dataObjectList.add(dataObject);
					}
				}

				if (bCreateNotExistItem) {
					for (DataObject object : dataObjectList) {
						String key = object.getKey();
						checkItem(hostName, key);
					}
				}

				try {
					SenderResult senderResult = zabbixSender
							.send(dataObjectList);
					if(!senderResult.success()){
						logger.error("send data to zabbix server error! senderResult:" + senderResult);
					}
				} catch (IOException e) {
					logger.error("send data to zabbix server error!", e);
				}

			}
		});
		consumer.init();

	}

	public void destory() {
		if(consumer != null){
			consumer.desotry();
		}
		if(zabbixApi != null){
			zabbixApi.destory();
		}
	}

	public ZabbixSender getZabbixSender() {
		return zabbixSender;
	}

	public void setZabbixSender(ZabbixSender zabbixSender) {
		this.zabbixSender = zabbixSender;
	}

	public ZabbixApi getZabbixApi() {
		return zabbixApi;
	}

	public void setZabbixApi(ZabbixApi zabbixApi) {
		this.zabbixApi = zabbixApi;
	}

	public boolean isbCreateNotExistHost() {
		return bCreateNotExistHost;
	}

	public void setbCreateNotExistHost(boolean bCreateNotExistHost) {
		this.bCreateNotExistHost = bCreateNotExistHost;
	}

	public String getHostGroup() {
		return hostGroup;
	}

	public void setHostGroup(String hostGroup) {
		this.hostGroup = hostGroup;
	}

	public boolean isbCreateNotExistItem() {
		return bCreateNotExistItem;
	}

	public void setbCreateNotExistItem(boolean bCreateNotExistItem) {
		this.bCreateNotExistItem = bCreateNotExistItem;
	}

	public String getZookeeper() {
		return zookeeper;
	}

	public void setZookeeper(String zookeeper) {
		this.zookeeper = zookeeper;
	}

	public String getTopic() {
		return topic;
	}

	public void setTopic(String topic) {
		this.topic = topic;
	}

	public String getGroup() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}
}
