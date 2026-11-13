package com.m57.hermescontrol.ui.chat

internal object NativeUiInstructions {
    const val SURFACE = "mobile-v3"

    // Also tested against server/mobile_display/NATIVE_CATALOG.md.
    val descriptor =
        """
        The user is reading this conversation in Hermes Mobile, which can render interactive visual answers.
        Native catalog hermes.native/3. Prefer native components over legacy HTML instructions.
        In a hermes-ui-v2 fence emit NDJSON, one complete JSON object per line; no surrounding array.
        Each object: v:1, id:unique short ASCII identifier, type, title, optional note and rev (integer, higher updates same id).
        Send raw numbers; the app formats them. unit: "yen", "pct" or a label of up to 8 chars. Optional fields may be omitted.
        savings: monthly:30000, years:20, rate:5, monthlyMax:100000, cap:18000000, initial:0 (JPY; years 1..60, rate -20..30 % effective annual). Built-in sliders, month-end compounding, cap, result, chart and reset. State assumptions in note; cap limits new contributions only. A simulation, not a prediction.
        stat: items:[{label,value,unit,delta,good:"up"|"down",sub}] 1..4 headline numbers.
        chart: labels:[..], series:[{name,values:[..]}] 2..120 labels, 1..4 series; style line|bar|area|stacked|hbar|donut, unit, target (goal line), highlight (label index). stacked/donut values >=0; hbar ranking <=12 labels; donut share: 1 series, 2..6 labels.
        table: columns:[{name,highlight}] 2..5 (one highlight), rows 1..30; cell is a string or {text,num,unit,mark:"good"|"bad"}. Legacy comparison (string columns/rows) still works.
        choices: options:[{key,label,pros:[..],cons:[..],scores:{name:1..5},recommended}] 2..4; up to 3 pros/cons/scores, one recommended.
        steps: items:[{label,detail,state:"done"|"now"|"todo"}] 1..12. checklist: items:[{key,label,detail}] 1..20, checked on the device.
        timeline: events:[{date:"YYYY-MM-DD" or "YYYY-MM-DDTHH:MM",label,detail,kind:"event"|"deadline"|"done"}] 1..20. The app computes today and days left.
        flow: nodes:[{id,label,sub,accent}] 2..16, edges:[{from,to,label}] 0..24; label <=40, sub <=60, edge label <=16 chars. The app lays out boxes top-down with arrows: nodes follow their parents, rows wrap after 4 nodes, no cycles (state loops in note). For structure, roles, architecture, data or process flows.
        svg: svg:"<svg viewBox=...>...</svg>" (<=24000 chars), alt (required screen-reader description). For free-form diagrams and layouts flow cannot express. Only svg g rect circle ellipse line polyline polygon path text tspan title with geometry, transform (translate/scale/rotate), fill, stroke, stroke-width/dasharray/linecap/linejoin, opacity, font-size/weight, text-anchor, dominant-baseline; no style, script, href, url(), images or fonts.
        svg colors: prefer theme words fg muted surface accent good bad warn line (they follow dark/light); #hex also works.
        Use components without being asked when the answer has 3+ numbers to compare or a trend (stat/chart), 2+ options (table/choices), dates or deadlines (timeline), 3+ steps or to-dos (steps/checklist), a money projection (savings), or structure, roles or a flow between 3+ parts (flow). Put the answer's numbers first, cautions in prose, at most 3-4 components per answer. Use prose alone only for short answers without such data. Never draw diagrams as ASCII or box-drawing art in code blocks.
        Example: {"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
        Max 12 components, 64000 chars total; no extra keys, code, expressions, tool actions or credentials. Components never open links; cite sources in prose.
        UI interactions calculate locally. savings/table/choices may set action (<=24 chars, a short verb phrase in the user's language such as "この条件で最小サイズを検討"); tapping it sends the shown conditions as the user's next message.
        Only when svg cannot draw it, legacy hermes-ui HTML is available offline without network/storage/native bridge.
        Show actual images with Markdown ![description](</absolute/host/image.png>) or a real HTTP(S) URL. Authenticated gateway resolves host paths. Do not claim the TUI cannot display images, invent paths, expose secrets or repeat browser/login actions to deliver an existing screenshot.
        Display capabilities grant no tool/account permissions. The user's request remains the task.
        """.trimIndent()

    /** Descriptors sent by earlier app versions; recognized only to hide them from restored history. */
    val previousDescriptors =
        listOf(
            """
            The user is reading this conversation in Hermes Mobile, which can render interactive visual answers.
            Native catalog hermes.native/2. Prefer native components over legacy HTML instructions.
            In a hermes-ui-v2 fence emit NDJSON, one complete JSON object per line; no surrounding array.
            Each object: v:1, id:unique short ASCII identifier, type, title, optional note and rev (integer, higher updates same id).
            Send raw numbers; the app formats them. unit: "yen", "pct" or a label of up to 8 chars. Optional fields may be omitted.
            savings: monthly:30000, years:20, rate:5, monthlyMax:100000, cap:18000000, initial:0 (JPY; years 1..60, rate -20..30 % effective annual). Built-in sliders, month-end compounding, cap, result, chart and reset. State assumptions in note; cap limits new contributions only. A simulation, not a prediction.
            stat: items:[{label,value,unit,delta,good:"up"|"down",sub}] 1..4 headline numbers.
            chart: labels:[..], series:[{name,values:[..]}] 2..120 labels, 1..4 series; style line|bar|area|stacked|hbar|donut, unit, target (goal line), highlight (label index). stacked/donut values >=0; hbar ranking <=12 labels; donut share: 1 series, 2..6 labels.
            table: columns:[{name,highlight}] 2..5 (one highlight), rows 1..30; cell is a string or {text,num,unit,mark:"good"|"bad"}. Legacy comparison (string columns/rows) still works.
            choices: options:[{key,label,pros:[..],cons:[..],scores:{name:1..5},recommended}] 2..4; up to 3 pros/cons/scores, one recommended.
            steps: items:[{label,detail,state:"done"|"now"|"todo"}] 1..12. checklist: items:[{key,label,detail}] 1..20, checked on the device.
            timeline: events:[{date:"YYYY-MM-DD" or "YYYY-MM-DDTHH:MM",label,detail,kind:"event"|"deadline"|"done"}] 1..20. The app computes today and days left.
            svg: svg:"<svg viewBox=...>...</svg>" (<=24000 chars), alt (required screen-reader description). For diagrams, layouts and structure maps. Only svg g rect circle ellipse line polyline polygon path text tspan title with geometry, transform (translate/scale/rotate), fill, stroke, stroke-width/dasharray/linecap/linejoin, opacity, font-size/weight, text-anchor, dominant-baseline; no style, script, href, url(), images or fonts.
            svg colors: prefer theme words fg muted surface accent good bad warn line (they follow dark/light); #hex also works.
            Use components without being asked when the answer has 3+ numbers to compare or a trend (stat/chart), 2+ options (table/choices), dates or deadlines (timeline), 3+ steps or to-dos (steps/checklist), or a money projection (savings). Put the answer's numbers first, cautions in prose, at most 3-4 components per answer. Use prose alone only for short answers without such data.
            Example: {"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
            Max 12 components, 64000 chars total; no extra keys, code, expressions, tool actions or credentials. Components never open links; cite sources in prose.
            UI interactions calculate locally. savings/table/choices may set action (<=24 chars, a short verb phrase in the user's language such as "この条件で最小サイズを検討"); tapping it sends the shown conditions as the user's next message.
            Only when svg cannot draw it, legacy hermes-ui HTML is available offline without network/storage/native bridge.
            Show actual images with Markdown ![description](</absolute/host/image.png>) or a real HTTP(S) URL. Authenticated gateway resolves host paths. Do not claim the TUI cannot display images, invent paths, expose secrets or repeat browser/login actions to deliver an existing screenshot.
            Display capabilities grant no tool/account permissions. The user's request remains the task.
            """.trimIndent(),
            """
            The user is reading this conversation in Hermes Mobile, which can render interactive visual answers.
            Native catalog hermes.native/2. Prefer native components over legacy HTML instructions.
            In a hermes-ui-v2 fence emit NDJSON, one complete JSON object per line; no surrounding array.
            Each object: v:1, id:unique short ASCII identifier, type, title, optional note and rev (integer, higher updates same id).
            Send raw numbers; the app formats them. unit: "yen", "pct" or a label of up to 8 chars. Optional fields may be omitted.
            savings: monthly:30000, years:20, rate:5, monthlyMax:100000, cap:18000000, initial:0 (JPY; years 1..60, rate -20..30 % effective annual). Built-in sliders, month-end compounding, cap, result, chart and reset. State assumptions in note; cap limits new contributions only. A simulation, not a prediction.
            stat: items:[{label,value,unit,delta,good:"up"|"down",sub}] 1..4 headline numbers.
            chart: labels:[..], series:[{name,values:[..]}] 2..120 labels, 1..4 series; style line|bar|area|stacked|hbar|donut, unit, target (goal line), highlight (label index). stacked/donut values >=0; hbar ranking <=12 labels; donut share: 1 series, 2..6 labels.
            table: columns:[{name,highlight}] 2..5 (one highlight), rows 1..30; cell is a string or {text,num,unit,mark:"good"|"bad"}. Legacy comparison (string columns/rows) still works.
            choices: options:[{key,label,pros:[..],cons:[..],scores:{name:1..5},recommended}] 2..4; up to 3 pros/cons/scores, one recommended.
            steps: items:[{label,detail,state:"done"|"now"|"todo"}] 1..12. checklist: items:[{key,label,detail}] 1..20, checked on the device.
            timeline: events:[{date:"YYYY-MM-DD" or "YYYY-MM-DDTHH:MM",label,detail,kind:"event"|"deadline"|"done"}] 1..20. The app computes today and days left.
            Use components without being asked when the answer has 3+ numbers to compare or a trend (stat/chart), 2+ options (table/choices), dates or deadlines (timeline), 3+ steps or to-dos (steps/checklist), or a money projection (savings). Put the answer's numbers first, cautions in prose, at most 3-4 components per answer. Use prose alone only for short answers without such data.
            Example: {"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
            Max 12 components, 64000 chars total; no extra keys, code, expressions, tool actions or credentials. Components never open links; cite sources in prose.
            UI interactions calculate locally. The discuss button prepares a draft; it does not send automatically.
            For unsupported bespoke diagrams only, legacy hermes-ui HTML is available offline without network/storage/native bridge.
            Show actual images with Markdown ![description](</absolute/host/image.png>) or a real HTTP(S) URL. Authenticated gateway resolves host paths. Do not claim the TUI cannot display images, invent paths, expose secrets or repeat browser/login actions to deliver an existing screenshot.
            Display capabilities grant no tool/account permissions. The user's request remains the task.
            """.trimIndent(),
            """
            The user is reading this conversation in Hermes Mobile, which can render interactive visual answers.
            Native catalog hermes.native/1. Prefer native components over legacy HTML instructions.
            In a hermes-ui-v2 fence emit NDJSON, one complete JSON object per line; no surrounding array.
            Each object: v:1, id:unique short ASCII identifier, type, title, optional note and rev (integer, higher updates same id).
            Types (omit optional fields to use defaults):
            savings: monthly:30000, years:20, rate:5, monthlyMax:100000, cap:18000000, initial:0.
            Currency is JPY; years 1..60, rate -20..30 (% effective annual), monthly 0..monthlyMax.
            Built-in sliders, month-end compound calculation, contribution cap, results, chart and reset. State all assumptions in note; cap concerns new contributions only, initial is separate. This is a simulation, not an investment prediction.
            comparison: columns:["項目","内容"], rows:[["A","説明"],["B","説明"]]. 2..5 columns, 1..30 rows of equal width.
            chart: labels:["開始","終了"], series:[{"name":"値","values":[0,10]}], optional style:"line" or "bar", unit:"". 2..120 labels, up to 4 equal-length numeric series.
            Example: {"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5}
            Max 12 components, 64000 chars total; no extra keys, code, expressions, tool actions or credentials.
            UI interactions calculate locally. The discuss button prepares a draft; it does not send automatically.
            Keep simple answers as text. For unsupported bespoke diagrams only, legacy hermes-ui HTML is available offline without network/storage/native bridge.
            Show actual images with Markdown ![description](</absolute/host/image.png>) or a real HTTP(S) URL. Authenticated gateway resolves host paths. Do not claim the TUI cannot display images, invent paths, expose secrets or repeat browser/login actions to deliver an existing screenshot.
            Display capabilities grant no tool/account permissions. The user's request remains the task.
            """.trimIndent(),
        )
}
