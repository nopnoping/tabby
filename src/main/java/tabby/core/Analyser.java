package tabby.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import soot.CompilationDeathException;
import soot.G;
import soot.Main;
import soot.Scene;
import soot.options.Options;
import tabby.config.GlobalConfiguration;
import tabby.config.SootConfiguration;
import tabby.core.collector.FileCollector;
import tabby.core.container.DataContainer;
import tabby.core.container.RulesContainer;
import tabby.core.scanner.CallGraphScanner;
import tabby.core.scanner.ClassInfoScanner;
import tabby.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static soot.SootClass.HIERARCHY;

/**
 * @author wh1t3P1g
 * @since 2020/10/10
 */
@Slf4j
@Component
public class Analyser {

    // lyf: 这里的container和scanner想表达的是什么意思？
    @Autowired
    private DataContainer dataContainer;
    @Autowired
    private ClassInfoScanner classInfoScanner;
    @Autowired
    private CallGraphScanner callGraphScanner;

    @Autowired
    private RulesContainer rulesContainer;
    // lyf: 该对象就是去获取文件，应该写为工具类，提供静态方法
    @Autowired
    private FileCollector fileCollector;


    public void run() throws IOException {
        boolean buildEnabled = GlobalConfiguration.IS_BUILD_ENABLE;
        boolean loadEnabled = GlobalConfiguration.IS_LOAD_ENABLE;
        Future<Boolean> future = null;
        if(loadEnabled){ // 用线程先删除neo4j中老数据
            future = dataContainer.cleanAll();
            if(!buildEnabled){
                while (!future.isDone()){
                    // do nothing 等待结束
                }
            }
        }

        if(buildEnabled){
            Map<String, String> dependencies = fileCollector.collectJdkDependencies();

            log.info("Get {} JDK dependencies", dependencies.size());
            log.info("Try to collect all targets");

            Map<String, String> cps = GlobalConfiguration.IS_EXCLUDE_JDK ? new HashMap<>():new HashMap<>(dependencies);
            Map<String, String> targets = new HashMap<>();
            // 收集目标
            GlobalConfiguration.rulesContainer = rulesContainer;
            if(!GlobalConfiguration.IS_JDK_ONLY){
                Map<String, String> files = fileCollector.collect(GlobalConfiguration.TARGET);
                cps.putAll(files);
                targets.putAll(files);
            }

            if(GlobalConfiguration.IS_JDK_ONLY
                    || GlobalConfiguration.IS_JDK_PROCESS){
                targets.putAll(dependencies);
            }

            // 添加必要的依赖，防止信息缺失，比如servlet依赖
            if(FileUtils.fileExists(GlobalConfiguration.LIBS_PATH)){
                Map<String, String> files = fileCollector.collect(GlobalConfiguration.LIBS_PATH);
                GlobalConfiguration.libraries.putAll(files);
            }

            for(Map.Entry<String, String> entry:GlobalConfiguration.libraries.entrySet()){
                cps.putIfAbsent(entry.getKey(), entry.getValue());
            }

            runSootAnalysis(targets, new ArrayList<>(cps.values()));
            dataContainer.count();
            dataContainer.save2CSV();
        }

        if(loadEnabled){
            G.reset();
            if(future != null){
                while (!future.isDone()){
                    // do nothing 等待结束
                }
            }
            save();
        }
    }

    public void runSootAnalysis(Map<String, String> targets, List<String> classpaths){
        try{
            SootConfiguration.initSootOption();
            addBasicClasses();
            log.info("Load basic classes");
            Scene.v().loadBasicClasses();
            log.info("Load dynamic classes");
            Scene.v().loadDynamicClasses();
            Scene.v().setSootClassPath(String.join(File.pathSeparator, new HashSet<>(classpaths)));
            // get target filepath
            List<String> realTargets = getTargets(targets);
            if(realTargets.isEmpty()){
                log.info("Nothing to analysis!");
                return;
            }
            Main.v().autoSetOptions();
            log.info("Target {}, Dependencies {}", realTargets.size(), classpaths.size());
            long start = System.nanoTime();
            // 类信息抽取
            classInfoScanner.run(realTargets);
            // 全量函数调用图构建
            callGraphScanner.run();

            rulesContainer.saveStatus();
            long time = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            log.info("Total cost {} min {} seconds."
                    , time/60, time%60);
//            if (!Options.v().oaat()) {
//                PackManager.v().writeOutput();
//            }
        }catch (CompilationDeathException e){
            if (e.getStatus() != CompilationDeathException.COMPILATION_SUCCEEDED) {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getTargets(Map<String, String> targets){
        Set<String> stuff = new HashSet<>();
        List<String> newIgnore = new ArrayList<>();
        targets.forEach((filename, filepath) -> {
            if(!rulesContainer.isIgnore(filename)){
                stuff.add(filepath);
                newIgnore.add(filename);
            }
        });
        rulesContainer.getIgnored().addAll(newIgnore);
        log.info("Total analyse {} targets.", stuff.size());
        Options.v().set_process_dir(new ArrayList<>(stuff));
        return new ArrayList<>(stuff);
    }

    public void addBasicClasses(){
        List<String> basicClasses = rulesContainer.getBasicClasses();
        for(String cls:basicClasses){
            Scene.v().addBasicClass(cls, HIERARCHY);
        }
    }

    public void save(){
        log.info("Start to save cache.");
        long start = System.nanoTime();
        dataContainer.save2Neo4j();
        long time = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        log.info("Cost {} min {} seconds."
                , time/60, time%60);
    }

}