package com.budwk.app;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import com.budwk.app.sys.models.*;
import com.budwk.app.sys.services.SysTaskService;
import com.budwk.app.task.services.TaskPlatformService;
import com.budwk.app.web.commons.auth.satoken.SaTokenContextImpl;
import com.budwk.app.web.commons.auth.satoken.SaTokenDaoRedisImpl;
import com.budwk.app.web.commons.auth.satoken.StpInterfaceImpl;
import com.budwk.app.web.commons.base.Globals;
import com.budwk.app.web.commons.ext.pubsub.WebPubSub;
import com.budwk.app.web.tags.*;
import lombok.extern.slf4j.Slf4j;
import org.beetl.core.GroupTemplate;
import org.nutz.boot.NbApp;
import org.nutz.dao.Chain;
import org.nutz.dao.Dao;
import org.nutz.dao.Sqls;
import org.nutz.dao.impl.FileSqlManager;
import org.nutz.dao.sql.Sql;
import org.nutz.dao.util.Daos;
import org.nutz.integration.jedis.JedisAgent;
import org.nutz.ioc.Ioc;
import org.nutz.ioc.impl.PropertiesProxy;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Mirror;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.annotation.ChainBy;
import org.nutz.mvc.annotation.Encoding;
import org.nutz.mvc.annotation.Localization;
import org.quartz.Scheduler;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContext;
import java.lang.management.ManagementFactory;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * @author wizzer@qq.com
 */
@IocBean(create = "init", depose = "depose")
@Localization(value = "locales/", defaultLocalizationKey = "zh_CN")
@Encoding(input = "UTF-8", output = "UTF-8")
@ChainBy(args = "chain/mvc-chain.json")
@Slf4j
public class MainLauncher {
    protected static final String PRE = "security.";
    @Inject("refer:$ioc")
    private Ioc ioc;
    @Inject
    private PropertiesProxy conf;
    @Inject
    private JedisAgent jedisAgent;
    @Inject
    private WebPubSub webPubSub;//???????????????????????????
    @Inject
    private GroupTemplate groupTemplate;
    @Inject
    private TaskPlatformService taskPlatformService;
    @Inject
    private SysTaskService sysTaskService;
    @Inject
    private Dao dao;

    public static void main(String[] args) throws Exception {
        NbApp nb = new NbApp().setArgs(args).setPrintProcDoc(true);
        nb.getAppContext().setMainPackage("com.budwk");
        nb.run();
    }

    public static NbApp warMain(ServletContext sc) {
        NbApp nb = new NbApp().setPrintProcDoc(true);
        nb.getAppContext().setMainPackage("com.budwk");
        return nb;
    }

    public void init() {
        Mvcs.X_POWERED_BY = "BudWK-V5 <budwk.com>";
        Globals.AppBase = Mvcs.getServletContext().getContextPath();
        Globals.AppRoot = Mvcs.getServletContext().getRealPath("/");
        //?????????????????????
        groupTemplate.registerTagFactory("cms_channel_list", () -> ioc.get(CmsChannelListTag.class));
        groupTemplate.registerTagFactory("cms_channel", () -> ioc.get(CmsChannelTag.class));
        groupTemplate.registerTagFactory("cms_article_list", () -> ioc.get(CmsArticleListTag.class));
        groupTemplate.registerTagFactory("cms_article", () -> ioc.get(CmsArticleTag.class));
        groupTemplate.registerTagFactory("cms_link_list", () -> ioc.get(CmsLinkListTag.class));
        init_sys();
        init_task();
        ioc.get(Globals.class);
        init_auth();
    }

    public void init_auth() {
        SaTokenConfig saTokenConfig = conf.makeDeep(SaTokenConfig.class, PRE);
        String tokenName = conf.get(PRE + "tokenName", "token");
        // ???????????????token????????????????????????????????? websocket ????????????????????????
        saTokenConfig.setTimeout(conf.getLong(PRE + "timeout", 86400));
        saTokenConfig.setTokenName(tokenName);
        saTokenConfig.setIsV(false);
        SaManager.setConfig(saTokenConfig);
        SaManager.setSaTokenContext(ioc.get(SaTokenContextImpl.class));
        SaManager.setSaTokenDao(ioc.get(SaTokenDaoRedisImpl.class));
        SaManager.setStpInterface(ioc.get(StpInterfaceImpl.class));
    }


    private void init_task() {
        if (log.isDebugEnabled() && !dao.exists("sys_qrtz_triggers") && !dao.exists("sys_qrtz_triggers".toUpperCase())) {
            //??????Quartz SQL??????
            String dbType = dao.getJdbcExpert().getDatabaseType();
            log.debug("dbType:::" + dbType);
            FileSqlManager fmq = new FileSqlManager("db/quartz/" + dbType.toLowerCase() + ".sql");
            List<Sql> sqlListq = fmq.createCombo(fmq.keys());
            Sql[] sqlsq = sqlListq.toArray(new Sql[sqlListq.size()]);
            for (Sql sql : sqlsq) {
                dao.execute(sql);
            }
        }
        if (0 == sysTaskService.count()) {
            //??????????????????
            Sys_task task = new Sys_task();
            task.setDisabled(true);
            task.setName("????????????");
            task.setJobClass("com.budwk.app.task.job.TestJob");
            task.setCron("*/5 * * * * ?");
            task.setData("{\"hi\":\"Wechat:wizzer | send red packets of support,thank u\"}");
            task.setNote("????????????wizzer | ?????????????????????????????????????????????");
            sysTaskService.insert(task);
        }
    }

    private void init_sys() {
        if (log.isDebugEnabled()) {
            //??????POJO??????????????????
            try {
                Daos.createTablesInPackage(dao, "com.budwk", false);
                //??????POJO??????????????????
                //Daos.migration(dao, "com.budwk", true, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // ??????????????????????????????????????????????????????
        if (0 == dao.count(Sys_user.class)) {
            //??????????????????
            Sys_config conf = new Sys_config();
            conf.setConfigKey("AppName");
            conf.setConfigValue("BudWk-V5 mini");
            conf.setNote("????????????");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("AppShrotName");
            conf.setConfigValue("budwk");
            conf.setNote("???????????????");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("AppDomain");
            conf.setConfigValue("http://127.0.0.1:8080");
            conf.setNote("????????????");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("AppFileDomain");
            conf.setConfigValue("");
            conf.setNote("??????????????????");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("AppUploadBase");
            conf.setConfigValue("/upload");
            conf.setNote("??????????????????");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("SessionOnlyOne");
            conf.setConfigValue("true");
            conf.setNote("???????????????????????????Session??????(??????????????????)");
            dao.insert(conf);
            conf = new Sys_config();
            conf.setConfigKey("WebNotification");
            conf.setConfigValue("false");
            conf.setNote("?????????????????????");
            dao.insert(conf);
            //???????????????
            Sys_unit unit = new Sys_unit();
            unit.setPath("0001");
            unit.setName("????????????");
            unit.setAliasName("System");
            unit.setUnitcode("system");
            unit.setLocation(0);
            unit.setAddress("??????-?????????-??????");
            unit.setEmail("wizzer@qq.com");
            unit.setTelephone("");
            unit.setHasChildren(false);
            unit.setParentId("");
            unit.setWebsite("https://budwk.com");
            Sys_unit dbunit = dao.insert(unit);
            //???????????????
            List<Sys_menu> menuList = new ArrayList<Sys_menu>();
            Sys_menu menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001");
            menu.setName("??????");
            menu.setNote("??????");
            menu.setAliasName("System");
            menu.setIcon("ti-settings");
            menu.setLocation(0);
            menu.setHref("");
            menu.setTarget("");
            menu.setShowit(true);
            menu.setHasChildren(true);
            menu.setParentId("");
            menu.setType("menu");
            menu.setPermission("sys");
            Sys_menu m0 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("00010001");
            menu.setName("????????????");
            menu.setNote("????????????");
            menu.setAliasName("Manager");
            menu.setIcon("ti-settings");
            menu.setLocation(1);
            menu.setHref("");
            menu.setTarget("");
            menu.setShowit(true);
            menu.setHasChildren(true);
            menu.setParentId(m0.getId());
            menu.setType("menu");
            menu.setPermission("sys.manager");
            Sys_menu m1 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010001");
            menu.setName("????????????");
            menu.setAliasName("Unit");
            menu.setLocation(0);
            menu.setHref("/platform/sys/unit");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.unit");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m2 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100010001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.unit.add");
            menu.setParentId(m2.getId());
            menu.setType("data");
            Sys_menu m21 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100010002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.unit.edit");
            menu.setParentId(m2.getId());
            menu.setType("data");
            Sys_menu m22 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100010003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.unit.delete");
            menu.setParentId(m2.getId());
            menu.setType("data");
            Sys_menu m23 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010002");
            menu.setName("????????????");
            menu.setAliasName("User");
            menu.setLocation(0);
            menu.setHref("/platform/sys/user");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.user");
            menu.setHasChildren(false);
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m3 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100020001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.user.add");
            menu.setParentId(m3.getId());
            menu.setType("data");
            Sys_menu m31 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100020002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.user.edit");
            menu.setParentId(m3.getId());
            menu.setType("data");
            Sys_menu m32 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100020003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.user.delete");
            menu.setParentId(m3.getId());
            menu.setType("data");
            Sys_menu m33 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010003");
            menu.setName("????????????");
            menu.setAliasName("Role");
            menu.setLocation(0);
            menu.setHref("/platform/sys/role");
            menu.setShowit(true);
            menu.setPermission("sys.manager.role");
            menu.setTarget("data-pjax");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m4 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100030001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.role.add");
            menu.setParentId(m4.getId());
            menu.setType("data");
            Sys_menu m41 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100030002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.role.edit");
            menu.setParentId(m4.getId());
            menu.setType("data");
            Sys_menu m42 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100030003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.role.delete");
            menu.setParentId(m4.getId());
            menu.setType("data");
            Sys_menu m43 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100030004");
            menu.setName("????????????");
            menu.setAliasName("SetMenu");
            menu.setLocation(4);
            menu.setShowit(false);
            menu.setPermission("sys.manager.role.menu");
            menu.setParentId(m4.getId());
            menu.setType("data");
            Sys_menu m44 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100030005");
            menu.setName("????????????");
            menu.setAliasName("SetUser");
            menu.setLocation(5);
            menu.setShowit(false);
            menu.setPermission("sys.manager.role.user");
            menu.setParentId(m4.getId());
            menu.setType("data");
            Sys_menu m45 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010004");
            menu.setName("????????????");
            menu.setAliasName("Menu");
            menu.setLocation(0);
            menu.setHref("/platform/sys/menu");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.menu");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m5 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100040001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.menu.add");
            menu.setParentId(m5.getId());
            menu.setType("data");
            Sys_menu m51 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100040002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.menu.edit");
            menu.setParentId(m5.getId());
            menu.setType("data");
            Sys_menu m52 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100040003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.menu.delete");
            menu.setParentId(m5.getId());
            menu.setType("data");
            Sys_menu m53 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010005");
            menu.setName("????????????");
            menu.setAliasName("Param");
            menu.setLocation(0);
            menu.setHref("/platform/sys/conf");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.conf");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m6 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100050001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.conf.add");
            menu.setParentId(m6.getId());
            menu.setType("data");
            Sys_menu m61 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100050002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.conf.edit");
            menu.setParentId(m6.getId());
            menu.setType("data");
            Sys_menu m62 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100050003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.conf.delete");
            menu.setParentId(m6.getId());
            menu.setType("data");
            Sys_menu m63 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010006");
            menu.setName("????????????");
            menu.setAliasName("Log");
            menu.setLocation(0);
            menu.setHref("/platform/sys/log");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.log");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m7 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100060001");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.log.delete");
            menu.setParentId(m7.getId());
            menu.setType("data");
            Sys_menu m71 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010007");
            menu.setName("????????????");
            menu.setAliasName("Task");
            menu.setLocation(0);
            menu.setHref("/platform/sys/task");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.task");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m8 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100070001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.task.add");
            menu.setParentId(m8.getId());
            menu.setType("data");
            Sys_menu m81 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100070002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.task.edit");
            menu.setParentId(m8.getId());
            menu.setType("data");
            Sys_menu m82 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100070003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.task.delete");
            menu.setParentId(m8.getId());
            menu.setType("data");
            Sys_menu m83 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010008");
            menu.setName("???????????????");
            menu.setAliasName("Route");
            menu.setLocation(0);
            menu.setHref("/platform/sys/route");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.route");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu m9 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100080001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.route.add");
            menu.setParentId(m9.getId());
            menu.setType("data");
            Sys_menu m91 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100080002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.route.edit");
            menu.setParentId(m9.getId());
            menu.setType("data");
            Sys_menu m92 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100080003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.route.delete");
            menu.setParentId(m9.getId());
            menu.setType("data");
            Sys_menu m93 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setParentId(m0.getId());
            menu.setDisabled(false);
            menu.setPath("00010002");
            menu.setName("????????????");
            menu.setAliasName("Config");
            menu.setType("menu");
            menu.setLocation(2);
            menu.setIcon("ti-pencil-alt");
            menu.setShowit(true);
            menu.setPermission("sys.config");
            menu.setHasChildren(true);
            Sys_menu pp1 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100020001");
            menu.setName("????????????");
            menu.setAliasName("Dict");
            menu.setLocation(0);
            menu.setHref("/platform/sys/dict");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.dict");
            menu.setParentId(pp1.getId());
            menu.setType("menu");
            Sys_menu d = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200010001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.dict.add");
            menu.setParentId(d.getId());
            menu.setType("data");
            Sys_menu d1 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200010002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.dict.edit");
            menu.setParentId(d.getId());
            menu.setType("data");
            Sys_menu d2 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200010003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(3);
            menu.setShowit(false);
            menu.setPermission("sys.manager.dict.delete");
            menu.setParentId(d.getId());
            menu.setType("data");
            Sys_menu d3 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100020002");
            menu.setName("????????????");
            menu.setAliasName("Api");
            menu.setLocation(0);
            menu.setHref("/platform/sys/api");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.api");
            menu.setParentId(pp1.getId());
            menu.setType("menu");
            Sys_menu appManger = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200020001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.api.add");
            menu.setParentId(appManger.getId());
            menu.setType("data");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200020002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.api.edit");
            menu.setParentId(appManger.getId());
            menu.setType("data");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000200020003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.api.delete");
            menu.setParentId(appManger.getId());
            menu.setType("data");
            dao.insert(menu);

            //???????????????????????????
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("00010003");
            menu.setName("????????????");
            menu.setNote("????????????");
            menu.setAliasName("InnerMsg");
            menu.setIcon("ti-bell");
            menu.setLocation(0);
            menu.setHref("");
            menu.setTarget("");
            menu.setShowit(true);
            menu.setHasChildren(true);
            menu.setParentId(m0.getId());
            menu.setType("menu");
            menu.setPermission("sys.msg");
            Sys_menu msg0 = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100030001");
            menu.setName("????????????");
            menu.setAliasName("All");
            menu.setLocation(0);
            menu.setHref("/platform/sys/msg/user/all");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.msg.all");
            menu.setParentId(msg0.getId());
            menu.setType("menu");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100030002");
            menu.setName("????????????");
            menu.setAliasName("Unread");
            menu.setLocation(1);
            menu.setHref("/platform/sys/msg/user/unread");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.msg.unread");
            menu.setParentId(msg0.getId());
            menu.setType("menu");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100030003");
            menu.setName("????????????");
            menu.setAliasName("Read");
            menu.setLocation(2);
            menu.setHref("/platform/sys/msg/user/read");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.msg.read");
            menu.setParentId(msg0.getId());
            menu.setType("menu");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("000100010009");
            menu.setName("????????????");
            menu.setAliasName("Msg");
            menu.setLocation(0);
            menu.setHref("/platform/sys/msg");
            menu.setTarget("data-pjax");
            menu.setShowit(true);
            menu.setPermission("sys.manager.msg");
            menu.setParentId(m1.getId());
            menu.setType("menu");
            Sys_menu msgManger = dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100090001");
            menu.setName("????????????");
            menu.setAliasName("Add");
            menu.setLocation(0);
            menu.setShowit(false);
            menu.setPermission("sys.manager.msg.add");
            menu.setParentId(msgManger.getId());
            menu.setType("data");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100090002");
            menu.setName("????????????");
            menu.setAliasName("Edit");
            menu.setLocation(1);
            menu.setShowit(false);
            menu.setPermission("sys.manager.msg.edit");
            menu.setParentId(msgManger.getId());
            menu.setType("data");
            dao.insert(menu);
            menu = new Sys_menu();
            menu.setDisabled(false);
            menu.setPath("0001000100090003");
            menu.setName("????????????");
            menu.setAliasName("Delete");
            menu.setLocation(2);
            menu.setShowit(false);
            menu.setPermission("sys.manager.msg.delete");
            menu.setParentId(msgManger.getId());
            menu.setType("data");
            dao.insert(menu);

            //???????????????
            Sys_role role = new Sys_role();
            role.setName("????????????");
            role.setCode("public");
            role.setAliasName("Public");
            role.setNote("All user has role");
            role.setUnitid("");
            role.setDisabled(false);
            dao.insert(role);
            role = new Sys_role();
            role.setName("???????????????");
            role.setCode("sysadmin");
            role.setAliasName("Sysadmin");
            role.setNote("System Admin");
            role.setUnitid("");
            role.setMenus(menuList);
            role.setDisabled(false);
            Sys_role dbrole = dao.insert(role);
            //???????????????
            Sys_user user = new Sys_user();
            user.setLoginname("superadmin");
            user.setUsername("???????????????");
            user.setCreateAt(System.currentTimeMillis());
            //String slat=R.UU32();
            //new Sha256Hash("1",ByteSource.Util.bytes(s), 1024).toHex();
            user.setSalt("r5tdr01s7uglfokpsdmtu15602");
            user.setPassword("1bba9287ebc50b766bff84273d11ccefaa7a8da95d078960f05f116e9d970fb0");
            user.setLoginIp("127.0.0.1");
            user.setLoginAt(0L);
            user.setLoginCount(0);
            user.setEmail("wizzer@qq.com");
            user.setLoginTheme("palette.3.css");
            user.setLoginBoxed(false);
            user.setLoginScroll(true);
            user.setLoginSidebar(false);
            user.setLoginPjax(true);
            user.setUnitId(dbunit.getId());
            user.setUnitPath(dbunit.getPath());
            user.setMenuTheme("left");
            Sys_user dbuser = dao.insert(user);
            //???????????????????????????(??????)
            dao.insert("sys_user_unit", org.nutz.dao.Chain.make("userId", dbuser.getId()).add("unitId", dbunit.getId()));
            dao.insert("sys_user_role", Chain.make("userId", dbuser.getId()).add("roleId", dbrole.getId()));
            //??????SQL??????
            FileSqlManager fm = new FileSqlManager("db/menu/");
            List<Sql> sqlList = fm.createCombo(fm.keys());
            Sql[] sqls = sqlList.toArray(new Sql[sqlList.size()]);
            for (Sql sql : sqls) {
                dao.execute(sql);
            }
            //?????????????????????
            dao.execute(Sqls.create("INSERT INTO sys_role_menu(roleId,menuId) SELECT @roleId,id FROM sys_menu").setParam("roleId", dbrole.getId()));
            //??????????????????????????????
            dao.execute(Sqls.create("update sys_menu set location=0 where path='00010003'"));
            //????????????????????????
            Sys_route route = new Sys_route();
            route.setDisabled(false);
            route.setUrl("/sysadmin");
            route.setToUrl("/platform/login");
            route.setType("hide");
            dao.insert(route);
        }
    }


    public void depose() {
        // ???mysql?????????,??????webapp??????mysql????????????,??????????????????
        try {
            Mirror.me(Class.forName("com.mysql.jdbc.AbandonedConnectionCleanupThread")).invoke(null, "shutdown");
        } catch (Throwable e) {
        }
        // ??????quartz??????????????????????????????
        try {
            ioc.get(Scheduler.class).shutdown(true);
        } catch (Exception e) {
        }
        // ??????com.alibaba.druid.proxy.DruidDriver???com.mysql.jdbc.Driver???reload??????warning?????????
        // ???webapp??????mysql????????????,??????????????????
        Enumeration<Driver> en = DriverManager.getDrivers();
        while (en.hasMoreElements()) {
            try {
                Driver driver = en.nextElement();
                String className = driver.getClass().getName();
                log.debug("deregisterDriver: " + className);
                DriverManager.deregisterDriver(driver);
            } catch (Exception e) {
            }
        }
        try {
            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName("com.alibaba.druid:type=MockDriver");
            if (mbeanServer.isRegistered(objectName))
                mbeanServer.unregisterMBean(objectName);
            objectName = new ObjectName("com.alibaba.druid:type=DruidDriver");
            if (mbeanServer.isRegistered(objectName))
                mbeanServer.unregisterMBean(objectName);
        } catch (Exception ex) {
        }
    }
}
