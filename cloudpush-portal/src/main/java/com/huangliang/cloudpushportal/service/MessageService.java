package com.huangliang.cloudpushportal.service;

import com.huangliang.api.constants.Constants;
import com.huangliang.api.constants.RedisPrefix;
import com.huangliang.api.entity.WebsocketMessage;
import com.huangliang.api.entity.request.SendRequest;
import com.huangliang.api.entity.response.Data;
import io.github.rhwayfun.springboot.rocketmq.starter.common.DefaultRocketMqProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推送消息处理类
 */
@Slf4j
@Service
public class MessageService {

    @Resource
    private DefaultRocketMqProducer producer;

    @Autowired
    private RedisTemplate redisTemplate;

    private static ExecutorService service = Executors.newCachedThreadPool();

    public void execute(SendRequest request) {
        //查询redis中所有的websocket服务
        Set<String> set = redisTemplate.keys(RedisPrefix.PREFIX_SERVERCLIENTS + "*");
        if (CollectionUtils.isEmpty(set)) {
            return;
        }
        //<服务端地址,对应的客户端结果集>
        Map<String,List<String>> hostClientsMap = new HashMap<>(set.size());
        if (request.getSendToAll()) {
            //根据服务下的设备标识推送
            //2.调用各个服务，让各个服务直接向自己维护的设备推送
            //serverKey => serverclients_10.9.217.160:9003
            for(String serverKey : set){
                service.execute(() -> {
                    try {
                        System.out.println(serverKey.split("_")[1]);
                        String url = "http://"+serverKey.split("_")[1]+"/message/send";
                        RestTemplate restTemplate = new RestTemplate();
                        restTemplate.postForEntity(url,request,Data.class);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                });
            }
        }else{
            Map<String, String> client = null;
            //根据参数中的客户端标识,找出所在的服务器，先对应的服务器发起推送
            for (String channelId : request.getTo()) {
                //遍历list 依次存入推送消息
                //根据channelId找到对应的客户端对象所对应websocket服务的实例名
                client = new HashMap();
                client = redisTemplate.opsForHash().entries(RedisPrefix.PREFIX_CLIENT + channelId);
                if (CollectionUtils.isEmpty(client)) {
                    log.info("不存在的客户端[{}]", channelId);
                    continue;
                }
                String host = client.get("host");
                if(hostClientsMap.containsKey(host)){
                    hostClientsMap.get(host).add(channelId);
                }else{
                    List<String> clients = new LinkedList<>();
                    clients.add(channelId);
                    hostClientsMap.put(host,clients);
                }
                for(String hostItem : hostClientsMap.keySet()){
                    service.execute(() -> {
                        RestTemplate restTemplate = new RestTemplate();
                        request.setTo(hostClientsMap.get(hostItem));
                        restTemplate.postForEntity("http://"+hostItem+"/message/send",request,Data.class);
                    });
                }
                //通过消息队列发送的方式废弃
                //producer.sendMsg(getInstants(RocketMQConfig.getWebsocketTopic(client.get("host")), channelId, form.getMsg()));
            }
        }
    }

    private Message getInstants(String topic, String channelId, String msg) {
        //构建message消息体
        Message message = new Message(topic, channelId, msg.getBytes());
        //由调用接口的方式触发消息
        message.putUserProperty(Constants.Trigger, WebsocketMessage.Trigger.HTTP.code + "");
        return message;
    }

}
