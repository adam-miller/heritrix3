<!DOCTYPE html>
<!--[if IE 8]> <html class="no-js lt-ie9" lang="en" > <![endif]-->
<!--[if gt IE 8]><!--> <html class="no-js" lang="en" > <!--<![endif]-->
<html>
<head>
	<meta charset="utf-8" />
	<meta name="viewport" content="width=device-width" />
	<link rel="stylesheet" href="/engine/static/css/normalize.css" />
	<link rel="stylesheet" href="/engine/static/css/foundation.min.css" />
	<link rel="stylesheet" href="/engine/static/css/heritrix.css" />
	<script src="/engine/static/js/vendor/custom.modernizr.js"></script>
	
	<title>Script in ${model.crawlJobShortName}</title>
	
	<link rel='stylesheet' href='/engine/static/codemirror/codemirror.css'>
	<link rel='stylesheet' href='/engine/static/codemirror/util/dialog.css'>
	<script src='/engine/static/codemirror/codemirror.js'></script>
	<script src='/engine/static/codemirror/mode/groovy.js'></script>
	<script src='/engine/static/codemirror/mode/clike.js'></script>
	<script src='/engine/static/codemirror/mode/javascript.js'></script>
	<script src='/engine/static/codemirror/util/dialog.js'></script>
	<script src='/engine/static/codemirror/util/searchcursor.js'></script>
	<script src='/engine/static/codemirror/util/search.js'></script>
</head>
<body>
	<div class="contain-to-grid">
		<nav class="top-bar">
			<ul class="title-area">
				<li class=" name">
					<h1>
						<img alt="Heritrix" class="hide-for-small"
							style="padding-top: 4px;"
							src="/engine/static/img/heritrix-logo.gif" /> <span
							class="hide-for-medium-up">Heritrix</span>
					</h1>
				</li>
				<li class="toggle-topbar menu-icon left"><a href="#"><span>Menu</span></a></li>
			</ul>

			<section class="top-bar-section">
				<!-- Left Nav Section -->
				<ul class="right">

					<li class="divider"></li>
					<li><a href="/engine">Engine</a></li>
					<li class="divider"></li>
					<li><a href="/engine/job/${model.crawlJobShortName}">Job "${model.crawlJobShortName}"</a></li>
					<li class="divider"></li>
				</ul>
			</section>
		</nav>
	</div>
	<div class="row">
		<div class="large-12 columns">
			<h3>Execute script for job <i><a href='/engine/job/${model.crawlJobShortName}'>${model.crawlJobShortName}</a></i></h3>
			<div class="row">
				<div class="large-12 columns">
					<div class="panel">
						<#if model.linesExecuted??>
						<span class='success'>${model.linesExecuted} lines executed<span>
						</#if>
						<#if model.ex??>
						<pre style='color:red; height:150px; overflow:auto'>
						${model.ex.printStackTrace()}
						</pre>
						</#if>
						<#if model.htmlOutput??>
						<fieldset><legend>htmlOut</legend>
						${model.htmlOutput}
						</fieldset>
						</#if>
						<#if model.rawOutput??>
						<fieldset><legend>rawOutput</legend>
							<pre>
						${model.rawOutput?html}
							</pre>
						</fieldset>
						</#if>
						<form method="POST">
							<div class="row">
								<div class="small-4 columns">
									<div class="row">
										<div class="small-5 columns">
											<label class="inline" for="engine">Script Engine: </label>
										</div>
										<div class="small-7 columns ">
											<select name="engine" id="selectEngine">
												<#list model.availableScriptEngines as scriptEngine>
												<option<#if selectedEngine=scriptEngine.engine> selected='selected'</#if> value='${scriptEngine.engine}'>${scriptEngine.language}</option>
												</#list>
											</select>
										</div>
									</div>
								</div>
							</div>
							
							<div class="row">
								<div class="large-1 columns left">
									<input type="submit" value="execute">
								</div>
							</div>
							<div class="row">
								<div class="small-12 columns">
									<textarea rows='20' style='width:100%' name='script' id='editor'>${script}</textarea>
								</div>
							</div>
							<div class="row">
								<div class="large-1 columns left">
									<input type="submit" value="execute">
								</div>
							</div>
						</form>
					</div>
				</div>
			</div>
		</div>
	</div>

	<div class="row">
		<div class="small-9 columns">
					The script will be executed in an engine preloaded
					with (global) variables:
			<div class="row">
				<div class="small-11 small-offset-1 columns">
					<ul>
						<li><code>rawOut</code>: a PrintWriter for arbitrary text output to this page</li>
						<li><code>htmlOut</code>: a PrintWriter for HTML output to this page</li>
						<li><code>job</code>: the current CrawlJob instance</li>
						<li><code>appCtx</code>: current job ApplicationContext, if any</li>
						<li><code>scriptResource</code>: the ScriptResource implementing this page, which offers utility methods</li>
					</ul>
				</div>
			</div>
		</div>
	</div>
<script>
	var modemap = {beanshell: 'text/x-java', groovy: 'groovy', js: 'javascript'};
	var selectEngine = document.getElementById('selectEngine');
	var editor = document.getElementById('editor');
	var cmopts = {
		    mode: modemap[selectEngine.value],
	        lineNumbers: true, autofocus: true, indentUnit: 4
		    }
	var cm = CodeMirror.fromTextArea(editor, cmopts);
	selectEngine.onchange = function(e) { cm.setOption('mode', modemap[selectEngine.value]); }
</script>



</body>
</html>




