/* 
 * Serposcope - SEO rank checker https://serposcope.serphacker.com/
 * 
 * Copyright (c) 2016 SERP Hacker
 * @author Pierre Nogues <support@serphacker.com>
 * @license https://opensource.org/licenses/MIT MIT License
 */
package com.serphacker.serposcope.task.google;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.serphacker.serposcope.db.google.GoogleDB;
import com.serphacker.serposcope.di.CaptchaSolverFactory;
import com.serphacker.serposcope.di.ScrapClientFactory;
//import com.serphacker.serposcope.di.ScraperFactory;
import com.serphacker.serposcope.models.base.Proxy;
import com.serphacker.serposcope.models.base.Run;
import com.serphacker.serposcope.models.google.GoogleSettings;
import com.serphacker.serposcope.models.google.GoogleRank;
import com.serphacker.serposcope.models.google.GoogleSearch;
import com.serphacker.serposcope.models.google.GoogleSerp;
import com.serphacker.serposcope.models.google.GoogleSerpEntry;
import com.serphacker.serposcope.models.google.GoogleTarget;
import com.serphacker.serposcope.scraper.captcha.solver.CaptchaSolver;
import com.serphacker.serposcope.scraper.google.GoogleScrapResult;
import com.serphacker.serposcope.scraper.google.scraper.GoogleScraper;
import com.serphacker.serposcope.scraper.http.ScrapClient;
import com.serphacker.serposcope.scraper.http.proxy.DirectNoProxy;
import com.serphacker.serposcope.scraper.http.proxy.ProxyRotator;
import com.serphacker.serposcope.task.AbstractTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serphacker.serposcope.scraper.http.proxy.ScrapProxy;
import java.util.stream.Collectors;
import com.serphacker.serposcope.di.GoogleScraperFactory;
import com.serphacker.serposcope.models.google.GoogleBest;
import com.serphacker.serposcope.models.google.GoogleTargetSummary;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class GoogleTask extends AbstractTask {

	protected static final Logger LOG = LoggerFactory.getLogger(GoogleTask.class);

	protected GoogleScraperFactory googleScraperFactory;
	protected CaptchaSolverFactory captchaSolverFactory;
	protected ScrapClientFactory scrapClientFactory;

	protected GoogleDB googleDB;
	protected ProxyRotator rotator;

	protected Run previousRun;
	protected final Map<Short, Integer> previousRunsByDay = new ConcurrentHashMap<>();
	protected final Map<Integer, List<GoogleTarget>> targetsByGroup = new ConcurrentHashMap<>();
	protected final Map<Integer, GoogleTargetSummary> summariesByTarget = new ConcurrentHashMap<>();

	protected LinkedBlockingQueue<GoogleSearch> searches;
	protected GoogleSettings googleOptions;
	protected final AtomicInteger searchDone = new AtomicInteger();
	protected final AtomicInteger captchaCount = new AtomicInteger();

	protected Thread[] threads;
	protected volatile int totalSearch;
	protected volatile boolean interrupted;

	protected CaptchaSolver solver;
	protected String httpUserAgent;
	protected int httpTimeoutMS;
	protected boolean updateRun;
	protected boolean shuffle = true;
	
	protected boolean customRun = false;

	protected GoogleTaskResult results;

	@Inject
	public GoogleTask(GoogleScraperFactory googleScraperFactory, CaptchaSolverFactory captchaSolverFactory,
			ScrapClientFactory scrapClientFactory, GoogleDB googleDB, @Assisted Run run) {
		super(run);
		this.googleScraperFactory = googleScraperFactory;
		this.captchaSolverFactory = captchaSolverFactory;
		this.scrapClientFactory = scrapClientFactory;
		this.googleDB = googleDB;
		this.updateRun = run.getId() == 0 ? false : true;

		httpUserAgent = ScrapClient.DEFAULT_USER_AGENT;
		httpTimeoutMS = ScrapClient.DEFAULT_TIMEOUT_MS;

	}
	
	public void setCustomRun(boolean customRun) {
		this.customRun = customRun;
	}

	public void setCustomSearches(List<GoogleSearch> searchList) {
		searches = new LinkedBlockingQueue<>(searchList);
	}
	
	public void setCustomTargets(List<GoogleTarget> targetList) {
		Map<Integer, Integer> previousScorePercent = new HashMap<>();

		if (previousRun != null) 
			previousScorePercent = googleDB.targetSummary.getPreviousScore(previousRun.getId());
		
		LOG.debug("Targets by group " + (targetsByGroup == null ? " is null" : "exists"));
		for(GoogleTarget target : targetList) {
			LOG.debug("Target " + (target == null ? " is null" : " exists"));
			LOG.debug("ByGroup " + (targetsByGroup.get(target.getGroupId()) == null ? " is null " : " exists"));
			if(targetsByGroup.get(target.getGroupId())== null)
				targetsByGroup.put(target.getGroupId(), new ArrayList<GoogleTarget>());
			
			targetsByGroup.get(target.getGroupId()).add(target);
			summariesByTarget.put(target.getId(), new GoogleTargetSummary(target.getGroupId(), target.getId(),
					run.getId(), previousScorePercent.getOrDefault(target.getId(), 0)));
		}
	}
	
	@Override
	public Run.Status doRun() {
		results = new GoogleTaskResult();
		solver = initializeCaptchaSolver();
		googleOptions = googleDB.options.get();

		initializePreviousRuns();
		if(!customRun) {
			initializeSearches();
			initializeTargets();
		}
		
	

		int nThread = googleOptions.getMaxThreads();
		List<ScrapProxy> proxies = baseDB.proxy.list().stream().map(Proxy::toScrapProxy).collect(Collectors.toList());

		if (proxies.isEmpty()) {
			LOG.warn("no proxy configured, using direct connection");
			proxies.add(new DirectNoProxy());
		}

		if (proxies.size() < nThread) {
			LOG.info("less proxy ({}) than max thread ({}), setting thread number to {}",
					new Object[] { proxies.size(), nThread, nThread });
			nThread = proxies.size();
		}

		rotator = new ProxyRotator(proxies);
		totalSearch = searches.size();

		startThreads(nThread);
		waitForThreads();

		finalizeSummaries();

		if (solver != null) {
			try {
				solver.close();
			} catch (IOException ex) {
			}
		}

		LOG.warn("{} proxies failed during the task", proxies.size() - rotator.list().size());

		int remainingSearch = totalSearch - searchDone.get();
		if (remainingSearch > 0) {
			run.setErrors(remainingSearch);
			LOG.warn("{} searches have not been checked", remainingSearch);
			return Run.Status.DONE_WITH_ERROR;
		}

		return Run.Status.DONE_SUCCESS;
	}

	public GoogleTaskResult getResults() {
		return results;
	}

	protected void startThreads(int nThread) {
		threads = new Thread[nThread];
		for (int iThread = 0; iThread < threads.length; iThread++) {
			threads[iThread] = new Thread(new GoogleTaskRunnable(this), "google-" + iThread);
			threads[iThread].start();
		}
	}

	protected void waitForThreads() {
		while (true) {
			try {
				for (Thread thread : threads) {
					thread.join();
				}
				return;
			} catch (InterruptedException ex) {
				interruptThreads();
			}
		}
	}

	protected void interruptThreads() {
		interrupted = true;
		for (Thread thread : threads) {
			thread.interrupt();
		}
	}

	protected boolean shouldStop() {
		if (searchDone.get() == totalSearch) {
			return true;
		}

		if (interrupted) {
			return true;
		}

		return false;
	}

	protected void incCaptchaCount(int captchas) {
		run.setCaptchas(captchaCount.addAndGet(captchas));
		baseDB.run.updateCaptchas(run);
	}

	protected void onSearchDone(GoogleSearch search, GoogleScrapResult res) {
		insertSearchResult(search, res);
		incSearchDone();
	}

	protected void incSearchDone() {
		run.setProgress((int) (((float) searchDone.incrementAndGet() / (float) totalSearch) * 100f));
		baseDB.run.updateProgress(run);
	}

	protected void insertSearchResult(GoogleSearch search, GoogleScrapResult res) {
		LOG.info("InsertSearchResult...");
		Map<Short, GoogleSerp> history = getHistory(search);

		GoogleSerp serp = new GoogleSerp(run.getId(), search.getId(), run.getStarted());
		for (String url : res.urls) {
			GoogleSerpEntry entry = new GoogleSerpEntry(url);
			entry.fillPreviousPosition(history);
			serp.addEntry(entry);
		}
		googleDB.serp.insert(serp);

		List<Integer> groups = googleDB.search.listGroups(search);
		for (Integer group : groups) {
			List<GoogleTarget> targets = targetsByGroup.get(group);
			if (targets == null) {
				continue;
			}
			for (GoogleTarget target : targets) {
				LOG.info("Processing..." + target.getName());
				int best = googleDB.rank.getBest(group, target.getId(), search.getId()).getRank();
				int rank = GoogleRank.UNRANKED;
				String rankedUrl = null;
				for (int i = 0; i < res.urls.size(); i++) {
					if (target.match(res.urls.get(i))) {
						rankedUrl = res.urls.get(i);
						rank = i + 1;
						break;
					}else
						LOG.info("PATTERN: " + target.getPattern() + " | URL: " + res.urls.get(i));
				}

				int previousRank = GoogleRank.UNRANKED;
				if (previousRun != null) {
					previousRank = googleDB.rank.get(previousRun.getId(), group, target.getId(), search.getId());
				}

				GoogleRank gRank = new GoogleRank(run.getId(), group, target.getId(), search.getId(), rank,
						previousRank, rankedUrl);
				results.addRank(gRank);

				googleDB.rank.insert(gRank);

				GoogleTargetSummary summary = summariesByTarget.get(target.getId());
				summary.addRankCandidat(gRank);

				if (rank != GoogleRank.UNRANKED && rank <= best) {
					googleDB.rank.insertBest(
							new GoogleBest(group, target.getId(), search.getId(), rank, run.getStarted(), rankedUrl));
				}
				LOG.info("Google Task: getting results... " + rankedUrl + " | " + rank);
			}
		}

		LOG.info("Google insertion finished!");
	}

	protected void initializeSearches() {
		List<GoogleSearch> searchList;
		if (updateRun) {
			searchList = googleDB.search.listUnchecked(run.getId());
		} else {
			searchList = googleDB.search.list();
		}
		if (shuffle) {
			Collections.shuffle(searchList);
		}
		searches = new LinkedBlockingQueue<>(searchList);
		LOG.info("{} searches to do", searches.size());
	}

	protected void initializeTargets() {
		Map<Integer, Integer> previousScorePercent = new HashMap<>();

		if (previousRun != null) {
			previousScorePercent = googleDB.targetSummary.getPreviousScore(previousRun.getId());
		}

		List<GoogleTarget> targets = googleDB.target.list();
		for (GoogleTarget target : targets) {
			targetsByGroup.putIfAbsent(target.getGroupId(), new ArrayList<>());
			targetsByGroup.get(target.getGroupId()).add(target);
			summariesByTarget.put(target.getId(), new GoogleTargetSummary(target.getGroupId(), target.getId(),
					run.getId(), previousScorePercent.getOrDefault(target.getId(), 0)));
		}

		if (updateRun) {
			List<GoogleTargetSummary> summaries = googleDB.targetSummary.list(run.getId());
			for (GoogleTargetSummary summary : summaries) {
				summariesByTarget.put(summary.getTargetId(), summary);
			}
		}
	}

	protected void initializePreviousRuns() {
		previousRun = baseDB.run.findPrevious(run.getId());
		if (previousRun == null) {
			return;
		}

		short[] days = new short[] { 1, 7, 30, 90 };

		for (short day : days) {
			List<Run> pastRuns = baseDB.run.findByDay(run.getModule(), run.getDay().minusDays(day));
			if (!pastRuns.isEmpty()) {
				previousRunsByDay.put(day, pastRuns.get(0).getId());
			}
		}
	}

	protected Map<Short, GoogleSerp> getHistory(GoogleSearch search) {
		Map<Short, GoogleSerp> history = new HashMap<>();

		for (Map.Entry<Short, Integer> entry : previousRunsByDay.entrySet()) {
			GoogleSerp serp = googleDB.serp.get(entry.getValue(), search.getId());
			if (serp != null) {
				history.put(entry.getKey(), serp);
			}
		}
		return history;
	}

	protected void finalizeSummaries() {
		Map<Integer, Integer> searchCountByGroup = googleDB.search.countByGroup();
		for (GoogleTargetSummary summary : summariesByTarget.values()) {
			summary.computeScoreBP(searchCountByGroup.getOrDefault(summary.getGroupId(), 0));
		}
		googleDB.targetSummary.insert(summariesByTarget.values());
	}

	protected GoogleScraper genScraper() {
		return googleScraperFactory.get(scrapClientFactory.get(httpUserAgent, httpTimeoutMS), solver);
	}

	@Override
	protected void onCrash(Exception ex) {

	}

	protected final CaptchaSolver initializeCaptchaSolver() {
		solver = captchaSolverFactory.get(baseDB.config.getConfig());
		if (solver != null) {
			if (!solver.init()) {
				LOG.info("failed to init captcha solver {}", solver.getFriendlyName());
				return null;
			}
			return solver;
		} else {
			LOG.info("no captcha service configured");
			return null;
		}

	}

	int getSearchDone() {
		return searchDone != null ? searchDone.get() : 0;
	}

}
