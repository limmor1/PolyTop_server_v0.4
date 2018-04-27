PolyTop consists of Android GUI (client) and SuperCollider DSP (server) that connect via a network.

Steps to initialize:
```C
// Creating an empty graph for nodes
~graph = TopNodeGraph.new();

// Adding SuperCollider nodes
~node0 = PolySO.newDefault(\tesxi, 1);
~graph.addOrigin(~node0, [0,0]);

// Adding Ableton Live node
~liveMidiBus = MIDIOut(0);
~node1 = PolyLiveSurf.newDefault(~liveMidiBus);

~graph.addOrigin(~node1, [0,0]);

// Algorithms to populate the graph
~algs = TopAlgs.new();

~genAlg1 = {|polyContents|
	/* randomly walk all values up/down for all non-static parameters by % step
	@params: PolyContents contents
	@return: PolyContents contents
	*/
	var defName, paramNames, pppv, behavior, midiOut, trans,
	nVoices, nParams, nSurf, stepSize = 1 / 100;

	if (polyContents.class == PolyState, {
		// Get all the insides of old PolyContents
		pppv = polyContents.pppv.deepCopy; // Array2D is mutable!

		defName = polyContents.defName;
		paramNames = polyContents.paramNames;

		nParams = pppv.rows; // each row is different param
		nVoices = pppv.cols; // each colums is different voice

		// Create new PolyContents
		polyContents = PolyState(defName, paramNames, pppv);

		// Transform
		// ... with transformation
		trans = {|values|
			values + (values * stepSize * Array.fill(values.size, {[-1,1].choose}))
		};
		// ... non-static params
		paramNames.do({|paramName|
			if ((paramName.asString.contains("s_").not && (paramName.asString != \t_released)), { // skip all params marked with "s_" (static)
				polyContents.editParam(paramName, trans);
			});
		});
	});

	if (polyContents.class == PolySO, {
		// Get all the insides of old PolyContents
		pppv = polyContents.pppv.deepCopy; // Array2D is mutable!

		defName = polyContents.defName;
		paramNames = polyContents.paramNames;

		nParams = pppv.rows; // each row is different param
		nVoices = pppv.cols; // each colums is different voice

		behavior = polyContents.behavior.collect({|strandBehavior| strandBehavior.duplicate });
		behavior.do(
			{|strandBehavior|
				var newEnvXYC;
				newEnvXYC = strandBehavior.envXYC;
				newEnvXYC = newEnvXYC.collect({|xyc| [xyc[0]*(0.01*[-1,1].choose), xyc[1], xyc[2]] });
				// transform behavior
				strandBehavior.envXYC = newEnvXYC;
		});

		// Create new PolyContents
		polyContents = PolySO(defName, paramNames, pppv, behavior);

		// Transform
		// ... with transformation
		trans = {|values|
			values + (values * stepSize * Array.fill(values.size, {[-1,1].choose}))
		};
		// ... non-static params
		paramNames.do({|paramName|
			if ((paramName.asString.contains("s_").not && (paramName.asString != \t_released)), { // skip all params marked with "s_" (static)
				polyContents.editParam(paramName, trans);
			});
		});
	});

	if (polyContents.class == PolyLiveSurf, {
		var idxSurf, nSurf;

		// Get all the insides of old PolyContents
		pppv = polyContents.pppv.deepCopy; // Array2D is mutable!

		nParams = pppv.rows; // each row is different param
		nSurf = polyContents.nSurf; // each colums is different voice
		idxSurf = polyContents.idxSurf;

		midiOut = polyContents.midiOut;

		// Create new PolyContents
		polyContents = PolyLiveSurf(pppv, midiOut, nSurf, idxSurf);

		// Transform
		// ... with transformation
		trans = {|value|
			value + (value * stepSize * {[-1,1].choose})
		};
		// ... all params
		nSurf.do({|sidx|
			nParams.do({|pidx|
				var newVal;
				newVal = trans.value(pppv.at(pidx, sidx)).value;
				polyContents.pppv.put(pidx, sidx, newVal);
			})
		})
	});

	polyContents;
};

~algs.genAlgs.put('default rand. walk (1%)', ~genAlg1);

// Bridge connects SuperCollider to PolyTop Android GUI
~bridge = TopBridge.new("192.168.8.181", ~graph, ~algs);

// Make sure the client is started for this step and the IP addresses are correct
~algs.sync(); // sync algs
~bridge.syncClient(); // sync graph
```
