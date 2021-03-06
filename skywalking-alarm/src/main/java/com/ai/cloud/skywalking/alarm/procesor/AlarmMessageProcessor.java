package com.ai.cloud.skywalking.alarm.procesor;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import redis.clients.jedis.Jedis;

import com.ai.cloud.skywalking.alarm.model.AlarmMessage;
import com.ai.cloud.skywalking.alarm.model.AlarmRule;
import com.ai.cloud.skywalking.alarm.model.ApplicationInfo;
import com.ai.cloud.skywalking.alarm.model.MailInfo;
import com.ai.cloud.skywalking.alarm.model.UserInfo;
import com.ai.cloud.skywalking.alarm.util.MailUtil;
import com.ai.cloud.skywalking.alarm.util.RedisUtil;
import com.ai.cloud.skywalking.alarm.util.RedisUtil.Executable;
import com.ai.cloud.skywalking.alarm.util.TemplateConfigurationUtil;

import freemarker.template.Template;
import freemarker.template.TemplateException;

public class AlarmMessageProcessor {

    private static Logger logger = LogManager
            .getLogger(AlarmMessageProcessor.class);

    static String mailTemplate = "";

    static {
        Properties properties = new Properties();
        try {
            properties.load(AlarmMessageProcessor.class.getResourceAsStream("/mail-template_new.config"));
            mailTemplate = properties.getProperty("template.default");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void process(UserInfo userInfo, AlarmRule rule) throws TemplateException, IOException, SQLException {
        Set<AlarmMessage> warningObjects = new HashSet<AlarmMessage>();
        Set<String> warningMessageKeys = new HashSet<String>();
        long currentFireMinuteTime = System.currentTimeMillis() / (10000 * 6);
        long warningTimeWindowSize = currentFireMinuteTime
                - rule.getPreviousFireTimeM();
        // 获取待发送数据
        if (warningTimeWindowSize >= rule.getConfigArgsDescriber().getPeriod()) {
            for (ApplicationInfo applicationInfo : rule.getApplicationInfos()) {
                for (int period = 0; period < warningTimeWindowSize; period++) {
                    String alarmKey = userInfo.getUserId()
                            + "-"
                            + applicationInfo.getAppCode()
                            + "-"
                            + (currentFireMinuteTime - period - 1);

                    warningMessageKeys.add(alarmKey);
                    setAlarmMessages(alarmKey, warningObjects);
                }
            }

            // 发送告警数据
            if (warningObjects.size() > 0) {
                if ("0".equals(rule.getTodoType())) {
                    logger.info("A total of {} alarm information needs to be sent {}", warningObjects.size(),
                            rule.getConfigArgsDescriber().getMailInfo().getMailTo());
                    // 发送邮件
                    String subjects = generateSubject(warningObjects.size(),
                            rule.getPreviousFireTimeM(), currentFireMinuteTime);
                    Map<String, Object> parameter = new HashMap<String, Object>();
                    parameter.put("warningObjects", warningObjects);
                    parameter.put("name", userInfo.getUserName());
                    parameter.put("startDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(
                            rule.getPreviousFireTimeM() * 10000 * 6)));
                    parameter.put("endDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(
                            currentFireMinuteTime * 10000 * 6)));
                    String mailContext = generateContent(mailTemplate, parameter);
                    if (mailContext.length() > 0) {
                        MailInfo mailInfo = rule.getConfigArgsDescriber()
                                .getMailInfo();
                        MailUtil.sendMail(mailInfo.getMailTo(),
                                mailInfo.getMailCc(), mailContext, subjects);
                    }
                }
            }

            // 清理数据
            for (String toBeRemovedKey : warningMessageKeys) {
                expiredAlarmMessage(toBeRemovedKey);
            }

            // 修改-保存上次处理时间
            dealPreviousFireTime(userInfo, rule, currentFireMinuteTime);
        }

    }

    private void dealPreviousFireTime(UserInfo userInfo, AlarmRule rule,
                                      long currentFireMinuteTime) {
        rule.setPreviousFireTimeM(currentFireMinuteTime);
        savePreviousFireTime(userInfo.getUserId(), rule.getRuleId(),
                currentFireMinuteTime);
    }

    private String generateSubject(int count, long startTime, long endTime) {
        String title = "[Warning] There were "
                + count
                + "  alarm information between "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(
                startTime * 10000 * 6))
                + " to "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(
                endTime * 10000 * 6));

        return title;
    }

    private void expiredAlarmMessage(final String key) {
    	RedisUtil.execute(new Executable<Long>() {
			@Override
			public Long exe(Jedis client) {
				return client.expire(key, 0);
			}
		});
    }

    private void savePreviousFireTime(final String userId, final String ruleId,
                                      final long currentFireMinuteTime) {
    	RedisUtil.execute(new Executable<Long>() {
			@Override
			public Long exe(Jedis client) {
				return client.hset(userId, ruleId, String.valueOf(currentFireMinuteTime));
			}
		});
    }

    private void setAlarmMessages(final String key, final Collection<AlarmMessage> warningTracingIds) {
    	RedisUtil.execute(new Executable<Object>() {
			@Override
			public Collection<String> exe(Jedis client) {
				Map<String, String> result = client.hgetAll(key);
		        if (result != null) {
		        	for(String traceid : result.keySet()){
		        		warningTracingIds.add(new AlarmMessage(traceid, result.get(traceid)));
		        	}
		        }
		        return null;
			}
		});
    }

    private String generateContent(String templateStr, Map parameter) throws IOException, TemplateException, SQLException {
        Template t = null;
        t = new Template(null, new StringReader(templateStr), TemplateConfigurationUtil.getConfiguration());
        StringWriter out = new StringWriter();
        t.process(parameter, out);
        return out.getBuffer().toString();
    }
}
