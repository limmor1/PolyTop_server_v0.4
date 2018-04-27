/* PolyTop Server classes 22 Apr 2017 */
/* Global definitions (debugging, closing lost-reference MIDI ports, global controls, etc...:
~glob_gain
~gainOn
~topBridgeBank
~globalEffects - Synths are added to tail of this group, so that effects can work
~maxPeakCPU - maximum peak CPU allowed before discarding messages
~polyTopWFSVoices - Dictionary, where WFS stores all souding continuous voices. Key is the track number, wfsIdx.
~wfsIdx - incremented at each new gesture, so that WFS UScore has infinite polyphony.

... a lot of variables in home_studio_mappings_2017.scd
*/

TopNodeMap {
	/*
	Defines an infinite circular map containing nodes that belong to origin node. Accessed by vector(phi, l) from origin.
	Implemented as nested list - rectangle with polar system borders. Columns - l-values, Rows - phi-values.
	Refer to Figure (PolyTop, Server, Google Keep)
	*/
	var
	<>map = nil, //2D List: size [nPhi, l], where nPhi is known.
	<>nPhi = 360, // total vector angle resolution (nRows of map)
	<>phiQuant = 8, // effective vector angle resolution. Adjusted by zoom in TopNodeMap.mapPhi // TODO: add to options settings initialization in server

	<>absCoords = nil, // [float x, float y] absolute coordinates of the map inside TopNodeGraph

	//origin variables
	<>origin = nil, // Node origin;
	<>id = nil, // int id, same as origin Node id, used to access this map.
	<>linkName = nil, // Name of incoming node link - genAlg name (for non-bridge links). TODO: review for bridge links
	<>linkerCoords = nil, // int[phi, l] - coords of matching parent node, used in walking the tree at node level
	<>inMaps= nil, // List<int TopNodeMap ids>. List of all incoming TopNodeMap ids.
	<>outMaps = nil; // List<int TopNodeMap ids>. List of all outgoing TopNodeMap ids.

	*new {|origin, absCoords, linkName|
		/*
		@params:TODO
		*/
		^super.new.init(origin, absCoords, linkName);
	}

	init {|origin, absCoords, linkName|
		/*
		n.b.
		- inMaps, outMaps are set externally, after initialization.
		- id is copied from origin, at initialization
		@params: PolyValue origin
		*/
		// 1. Error checking, TODO expand
		if (origin.class != TopNode, { Error("TopNodeMap.init(): origin not of type TopNode").throw; });
		if ((absCoords.class != Array) || (absCoords.size != 2), {Error("TopNodeMap:init(): absCoords of wrong type").throw; });

		this.nPhi = nPhi;
		this.origin = origin;
		this.absCoords = absCoords;
		this.linkName = linkName;

		this.id = this.origin.id; // TopNodeMap.id same as its origin ID.

		// default linkName, inMaps, outMap values
		if (linkName == nil, {
			this.linkName = "origin"
		}, {
			this.linkName = linkName;
		});

		if (inMaps == nil, {
			this.inMaps = List.new(5);
		}, {
			this.inMaps = inMaps;
		});

		if (outMaps == nil, {
			this.outMaps = List.new(5);
		}, {
			this.outMaps = outMaps;
		});

		// create a first column in phi/l table, all same as origin, set relCoords [phi, 0].
		this.map = List.newFrom(Array.fill(nPhi, {
			|idx|
			var newLineageOrigin;
			newLineageOrigin = this.origin.duplicate();
			newLineageOrigin.relCoords = [idx, 0];
			List.newFrom([newLineageOrigin])
		}));
	}

	addNode {|aNode, phi, l|
		/* adds Node to TopNodeMap at coordinates.
		- Used internally, when loading all Nodes from save
		*/
		// ("This map[phi][l]: " + this.map[phi][l] + ", lineage size: " + this.map[phi].size).postln; // DEBUG

		if (this.map[phi].size < l,
			{
				Error.new("TopNodeMap.addNode(): can't add at l " + l + ". Lineage only has " + this.map[phi].size + " Nodes.").throw;
				^nil
		});

		if (this.map[phi][l] == nil,
			{ this.map[phi].add(aNode) },
			{ this.map[phi][l] = aNode });
	}

	setLinkerCoords {|relCoords|
		/* This method is used to copy over relCoords of a matching node from parent map, so that walking the tree at node level is possible.
		*/
		this.linkerCoords = this.origin.relCoords;
	}

	getNodePolar {|relCoords, genAlg|
		/* gets node by polar coordinates. Dynamic - generates new nodes */
		var targetNode, phi, l, kl;

		if ((relCoords.class != Array) || (relCoords.size != 2), {Error("TopNodeMap:getNodePolar(): relCoords of wrong type").throw; });
		// if ((relCoords[0] < 0) || (relCoords[1] < 0), {Error("TopNodeMap:getNodePolar(): relCoords can't be negative'").throw;} ); // DEPRECATED, relCoords [-pi, pi]
		phi = relCoords[0]; l = relCoords[1];

		// map phi for lookup
		phi = this.mapPhi(phi);

		// map l for lookup
		kl = 0.1; // if kl == 1, then 1:1 ( transformation:pixel )
		l = this.mapLNodeLin(l, (10 * 11.2) / 2, kl);// Node-linear density, where (10 * 11.2) is node diameter.
		// l = this.mapLCPL(l, 10*11.2, 500, 1.05, 10);// CPL-shape density

		if (this.map.at(phi) == nil,
			{
				targetNode = nil;
				// ("TopNodeMap.getNodePolar(): target node is nil").postln; //  DEBUG
			},
			{
				targetNode = this.map.at(phi).at(l);
				// ("TopNodeMap.getNodePolar(): target node is " + targetNode).postln; // DEBUG

			}
		);

		if ( targetNode != nil, {
			// "    ...node retrieved.".postln; // DEBUG
			^targetNode;
		},
		{
			// if targetNode == nil, generate
			var lineage, nAdded, endNode, newNode;

			// "TopNodeMap.getNodePolar(): cell empty, generating lineage...".postln; // DEBUG

			lineage = this.map.at(phi);
			// ("TopNodeMap.getNodePolar(): lineage retrieved - " + lineage).postln; // DEBUG

			nAdded = (l + 1) - lineage.size;
			nAdded.do({
				endNode = lineage.last;
				newNode = endNode.getNode(genAlg);
				lineage.add(newNode);
			});
			// "    ...node created.".postln; // DEBUG
			^lineage.last;
		});
	}

	mapPhi {|radPhi, zoom = 1|
		/* maps phi from PolyTop client to server. Adjusts resolution to zoom (TODO)
		@params: double radPhi - [0, pi]; double zoom - allows more lineages while zoomed in
		@return: absPhi - phi that is looked up in TopMap table. absPhi < TopMap.phiRes
		*/
		var absPhi;
		absPhi = radPhi.linlin(-pi, pi, 0, this.phiQuant).trunc * (360 / this.phiQuant);// TODO: replace 360 to TopMap.phiRes
		// ("TopBridge.mapPhi: mapped PHI " + absPhi).postln; // DEBUG
		^absPhi;
	}

	mapLNodeLin {|l, xNodeR, kl|
		var lScaled, xNodeConst;
		/*
		@params:
		nodeR - Node radius size (visual boundary of Node), default 10*11.2 pixels,
		kl - growth rate coefficient for linear function.

		@return: lScaled (int) - lookup index L of PHI/L table, scaled to mapping function.

		This function maps incoming L values into shape that offers no change around Node
		*/

		// derived constants
		xNodeConst = (xNodeR * 1).trunc; // x-boundary after which l change starts

		// error checking
		if (xNodeR < 0, { Error.new("mapCEL: xNodeR wrong value").throw });

		// linear change
		if (l > xNodeConst, {
			lScaled = (l * kl) - (xNodeConst * kl);
			^lScaled });
		^0;
	}

	mapLCPL {|l, xNodeR, xFarL, kp, kl|
		var lScaled, xNodeConst;
		/*
		@params:
		nodeR - Node radius size (visual boundary of Node), default 10*11.2 pixels,
		xFarL - x-boundary after which l change is linear,
		kp - growth rate coefficient for power function,
		kl - growth rate coefficient for linear function.

		@return: lScaled (int) - lookup index L of PHI/L table, scaled to mapping function.

		This function maps incoming L values into shape that offers no change around Node, power-function growth close to Node and linear growth far away from Node (Constant-Power-Linear - CPL function)
		*/

		// derived constants
		xNodeConst = (xNodeR * 1.25).trunc; // x-boundary after which l change is power-function.

		// error checking
		if (xNodeR < 0, { Error.new("mapCEL: xNodeR wrong value").throw });
		if ((xFarL < 0) || (xFarL < xNodeR) , { Error.new("mapCEL: xFarL wrong value").throw });

		// CPL change
		if (l > xFarL, {
			lScaled = (l * kl) - (xFarL * kl) + (xFarL.pow(kp) - xNodeConst.pow(kp)); // remove offsets
			^lScaled });

		if (l > xNodeConst, {
			lScaled = l.pow(kp) - xNodeConst.pow(kp); // remove offset
			^lScaled });
		^0;
	}

	displayMap{
		/* Prints out the current map in ASCII as a rectangle with polar axes (phi * l)
		*/

		this.nPhi.do({|phi|
			var lineage;
			lineage = this.map.at(phi);
			if (lineage != nil, {
				lineage.size.do({
					"*_".post;
				});
			});
			"".postln;
		});
		100.do({"oo ".post;});
		"".postln;
	}

	toSyncJSON {
		/* representation of TopNodeMap for sending to PolyTop Client as JSON. Used in TopNodeMap sync string
		*/
		var interString;
		interString = Dictionary.new();
		interString.put(\originID, this.origin.id);
		interString.put(\linkName, this.linkName);
		interString.put(\absCoords, this.absCoords);
		interString.put(\outLinks, this.outMaps);
		interString.put(\inLinks, this.inMaps);
		interString.put(\phiQuant, this.phiQuant);

		^JSON.stringify(interString);
	}

	asCompileString {
		var interString;
		interString = "TopNodeMap.new(" + this.origin.asCompileString + ", " + this.absCoords.asCompileString + ", "+ this.linkName.asCompileString + ").inMaps_(" + this.inMaps + ").outMaps_(" + this.outMaps + ").linkerCoords_(" + this.linkerCoords + ")";

		//this part adds all non-origin Nodes of the map (all lineages, l>0)
		for (0, this.nPhi,
			{|iPhi|
				var lineage;
				lineage = this.map[iPhi];
				if (lineage.size > 1, // if map has added nodes
					{ for (1, (lineage.size - 1),
						{|iL|
							interString = interString + ".addNode(" + lineage[iL].asCompileString + "," + iPhi + "," + iL + ")";
					});
				});
		});
		^interString;
	}

	asCompileStringSimple {
		var interString;
		interString = "TopNodeMap.new(" + this.origin.asCompileString + ", " + this.absCoords.asCompileString + ", "+ this.linkName.asCompileString + ").inMaps_(" + this.inMaps + ").outMaps_(" + this.outMaps + ").linkerCoords_(" + this.linkerCoords + ")";

		// no child nodes are added!
		^interString;
	}
}

TopNodeGraph {
	/* Defines surface with nodes of infinite size and density. Functions:
	- holds all TopNodeMaps and their nodes
	- access TopNodeMaps via ID.
	- access via abs coordinates.
	*/
	var
	<>allMaps = nil; // Dictionary of all TopNodeMap maps

	*new {|maps|
		/*
		empty constructor
		*/
		^super.new.init(maps);
	}

	init {|maps|
		/*
		params: Array maps
		*/
		// ("TopNodeGraph.init(): maps - " + maps).postln; // Julius - debug
		this.allMaps = Dictionary.new();

		if (maps != nil, {
			maps.do({|map|
				if (map.class != TopNodeMap, { Error("TopNodeGraph.init(): one of supplied maps is not of type TopNodeMap!").throw; });
				this.addNodeMap(map);
		})});
	}

	addOrigin {|state, absCoords|
		/* Convenience method: add an origin to TopNodeGraph manually */
		var topID = 0;
		this.allMaps.do({|map|
			if (map.origin.id > topID, { topID = map.origin.id })
		});

		// ("TopNodeGraph.addNode(): topID is" + topID +"; Adding new node with ID " + (topID+1)).postln; // DEBUG

		this.addNodeMap(TopNodeMap.new(
			TopNode.new(state, [0,0]).setID(topID+1),
			absCoords,
			"origin")
		);
	}

	addNodeMap {|map|
		/* adds TopNodeMap - origin node with its circular map
		@params:  int[phi, l] coords. Absolute coordinates;
		*/
		if (map.class != TopNodeMap,
			{Error("TopNodeMap.addNodeMap(): map of wrong type ").throw; },
			{
				this.allMaps.put(map.origin.id, map);
				"TopNodeMap added.".postln;
				// ("TopNodeMap.allMaps after addition - " +  this.allMaps).postln; // Julius - debug
			}
		);
	}

	getNodeMap {|id|
		/* gets TopNodeMap with matching origin.id
		@params: TopNode.id;
		@return: TopNodeMap
		*/
		var idx;
		allMaps.do({|map|
			if (map.origin.id == id, { ^map; } );
		});
		// if map not found - return nil
		^nil;
	}

	getStateSequence {|mapA, mapB|
		/* returns an array of Nodes between mapA and mapB.
		- forward sequence supported relative to the TopNodeGraph
		- searches NodeGraph for the intermediate nodes if mapA/B are not directly linked.
		- walking backwards, uses TopNode.relCoords to reach origin, then TopNodeMap.linkerCoords ++ TopNodeMap.inMaps to find the end of parent lineage
		- TODO: resolve cyclical situations, when converging nodes are present.
		- TODO: implement mixed direction sequence.
		- TODO: rename <Node> top <Map> to be technically correct.
		- TODO: implement as TopNodeMap method (makes more sense)

		@params: int mapA (ID), int mapB (ID)
		@return: Array<Node>
		*/
		// 1. get sequence of TopMaps
		var nodeSeq, stateSeq = [], linkerCoordArray;

		// "getStateSequence called".postln; // DEBUG
		[mapA, mapB].postln;

		// 2. get sequence of TopNodes in segment.
		nodeSeq = this.getMapSequence(mapA, mapB, mapA);
		// ("Node sequence: " + nodeSeq).postln; // DEBUG

		if (nodeSeq != nil,
			{ // if found Map downstream, get state sequence
				for (0, nodeSeq.size - 2, //
					{|idx|
						if (nodeSeq[idx].outMaps.size != 0,
							{ var targetCoords, states, phi, l;
								targetCoords = nodeSeq[idx + 1].linkerCoords;
								phi = targetCoords[0];
								l = targetCoords[1];
								// ("Phi: " + phi + "; l: " + l).postln; // DEBUG
								states = nodeSeq[idx].map[phi][..l]; // all states until L
								// ("Phi lineage size: " +  nodeSeq[idx].map[phi].size).postln;
								// ~states = states; // DEBUG
								stateSeq = stateSeq ++ states;
						});
				});
			},
			{ // if can't find Map downstream, try upstream
				nodeSeq = this.getMapSequenceUp(mapA, mapB)
				// ... then get state sequence
			}
		);

		^stateSeq;

	}

	getMapSequence {|mapA, mapB|
		/* helper method. Walks tree recursively until it finds the path node->mapB. mapB must be downstream only
		@return: list[node, node, node...]
		*/
		"getMapSequence called".postln; // DEBUG
		[mapA, mapB].postln;
		if (mapA.id == mapB.id,
			{
				"End cond.".postln;
				^[mapA]

			}, // end condition, node found, start trace back
			{
				if (mapA.outMaps.size == 0,
					{
						("End branch - node not found").postln; //DEBUG
						^ nil
					}, // end-condition - node not found,
					{
						var ret;
						// "Children present - Continue search...".postln;
						mapA.outMaps.do({|nodeID|
							var subNode;
							subNode = this.getNodeMap(nodeID);
							ret = this.getMapSequence(subNode, mapB); // continue recursively until end condition
							if (ret != nil, { ^([mapA] ++ ret) });
						});

						("ret: " + ret).postln; // DEBUG
						^ret; // return along the recursive tree gathering all returns
						// n.b. if this is not explicitly returned, then the function will return not the last value, but the Class! (TopNodeGraph)
					}
				)
			}
		)
	}

	getMapSequenceUp {|mapA, mapB|
		/* gets sequence A-B, where A <- B (upstream)
		*/
		var ret;
		if (mapA.id == mapB.id,
			{ ^[mapA] },
			{
				if (mapA.inMaps.size == 0,
					{ ^nil },
					{ var superNode;
						superNode = this.getNodeMap(mapA.inMaps[0]); // TODO: add support for bridging (multiple inMaps)
						ret = this.getMapSequenceUp(superNode, mapB);
						if (ret != nil, { ^([mapA] ++ ret) });
				});
				^ret;
		});
	}

	getNodesBySynthDef {|defName|
		/* collects all nodes that belong to the same synthdef and thus can be interpolated
		@params: Symbol defname
		@return: Array maps
		*/
		var res;
		res = List.new();
		this.allMaps.do({|map|
			var relativeCoords, pointRelative, pointOrigin, pointAbsolute;
			// ("map.origin.getContents.defName: " + map.origin.getContents.defName).postln;
			// ("defName: " + defName).postln;

			if (map.origin.getContents.defName.asString == defName.asString, { res.add(map) });
		});
		// ("TopNodeGraph.getNodeBySynthDef(): res - " + res).postln; // DEBUG
		^res;
	}

	getNodesById {|combTargets|
		/* convenience method that takes in a list of node ids and returns all nodes.
		Does error checking that all Nodes belong to same synthDef
		@params: combTargets[int id, int id, int id...]
		@return: combTargets[TopNodeMap node, TopNodeMap node ...]
		*/
		var nodes, defName, nodeMap;
		nodes = combTargets.collect({|id|
			("    node ID: " + id + ", id type :" + id.class).postln; // DEBUG
			nodeMap = this.getNodeMap(id);
			~this = this; // todo: DEBUG?
			~nodeMap = nodeMap; // todo: DEBUG?
			("    nodeMap for id " + id + " is TopNodeMap " + nodeMap).postln;
			nodeMap
		});
		("getNodeByID(): nodes - " + nodes).postln; // DEBUG

		defName = nodes[0].origin.getContents.defName;
		nodes.do({|node|
			var nextName;
			nextName = node.origin.getContents.defName;
			if (nextName != defName, {
				Error.new("getNodesById(): selected comb target nodes belong to different synthDefs!").throw;
				^[];
			});
		});
		^nodes;
	}

	getTargetDistances {|x, y, combTargets|
		/* Gets distances between combinedNode and all its targets
		@params: int x, int y, TopNodeMaps[] combTargets
		@return: double[] distances
		*/
		var distances, absCoords, dist2D;

		absCoords = combTargets.collect({|map| map.absCoords});
		// ("absCoords: "+absCoords).postln; // DEBUG

		dist2D = {|a, b|
			sqrt((b[0] - a[0])**2 + (b[1] - a[1])**2)
		};
		distances = absCoords.collect({|b|
			var dis;
			dis = dist2D.([x,y], b);
			if (dis == 0, { dis = dis + 0.000000001 }); // never return absolute distance
			dis;
		});
		// ("dists:" + distances).postln; //DEBUG
		^distances;
	}

	combineNodes {|x, y, combTargets, combAlg|
		/*
		combines nodes according to combAlg that takes multiple contents and returns one
		@params:  int[x, y] coords. Absolute coordinates; combTargets (optional) - [map, map, map, map...]. combAlg as define in initDefaultAlgs()
		@return: TopNode combined node, combination of all targets
		*/
		var oldNode, newNode, dists, distCoefs, targContents;
		if (combTargets.size == 0, { Error("TopNodeGraph.combineNodes(): 0 nodes to combine!").throw });

		oldNode =  combTargets[0].origin;
		// ("initial node: " + oldNode).postln;
		if (combTargets.size == 1, { ^oldNode.duplicate() });

		dists = 1.0 / this.getTargetDistances(x, y, combTargets); // 1/dist: closer = higher weight
		distCoefs = dists.collect({|dist| dist/dists.sum});
		// ("dist coeffs:" + distCoefs).postln; //DEBUG

		targContents = combTargets.collect({|map| map.origin.getContents });
		newNode = TopNode.new(combAlg.(targContents, distCoefs), [0,0]);

		^newNode;
	}

	saveGraph {|path =  "~/Desktop/nodeGraph.txt"|
		/* Saves node graph data as interpretable SC command in txt file
		- provide full or home-based path for specific save destination.
		- provide single word without "/" for quicksaving.
		*/
		var interString, file;

		"Now saving TopNodeGraph data... (please be patient)".postln;
		interString = "~load = " + this.asCompileString;

		if (not(path.contains("/")), { // if quicksave
			path = "~/Desktop/" ++ path;
		});

		file = File(path.standardizePath,"w");
		file.write(interString + "\n");
		file.close;

		("TopNodeGraph: saved to path " + path).postln;
	}

	saveGraphSimple {|path =  "~/Desktop/nodeGraph.txt"|
		/* Saves node graph data as interpretable SC command in txt file
		- provide full or home-based path for specific save destination.
		- provide single word without "/" for quicksaving.
		- n.b. does not save intermediate nodes to speed up save/load time (so no PolyEventGens will work)
		*/
		var interString, file;

		"Now saving TopNodeGraph data... (please be patient)".postln;
		interString = "~load = " + this.asCompileStringSimple;

		if (not(path.contains("/")), { // if quicksave
			path = "~/Desktop/" ++ path;
		});

		file = File(path.standardizePath,"w");
		file.write(interString + "\n");
		file.close;

		("TopNodeGraph: saved to path " + path).postln;
	}

	*loadGraphDebug {|path =  "~/Desktop/nodeGraph.txt"|
		/* Loads node graph data from text file as interpretable command.
		For debug only - SLOW! Expect waiting time of couple of minutes
		- provide full or home-based path for specific save destination.
		- provide single word without "/" for quicksaving.
		@params: file to load (contains single interpretable cmd of TopNodeGraph
		@return: TopNodeGraph
		*/

		var interString, nodeGraph, file;

		"Now loading TopNodeGraph data... (please be patient)".postln;

		if (path.contains("/"), {
			file = File(path.standardizePath,"r");
			interString = file.readAllString();
			file.close;
		}, {
			path = "~/Desktop/" ++ path;
		});

		// 1. read in data
		file = File(path.standardizePath,"r");
		interString = file.readAllString();
		file.close;

		// 2. make TopNodeGraph
		nodeGraph = interString.interpret;

		("loadGraph(): loaded graph from command...\n" + interString).postln;
		^nodeGraph;
	}

	*loadGraph {|path = "~/Desktop/nodeGraph.txt"|
		/* Loads node graph data from text file to global var ~load. A bit faster than loadGraphDebug.
		- provide full or home-based path for specific save destination.
		- provide single word without "/" for quicksaving.
		@params: file to load (contains single interpretable cmd of TopNodeGraph)
		@return: TopNodeGraph
		*/

		var interString, nodeGraph, file;
		"Now loading TopNodeGraph data... (please be patient)".postln;
		if (path.contains("/"), {
			path.standardizePath.load; // loads graph to global var ~load
		}, {
			path = "~/Desktop/".standardizePath ++ path;
			path.load;
		});
		("TopNodeGraph.load(): loaded from path " + path + " (globally accessible through ~load").postln;
	}


	asCompileString {
		/* representation of TopNodeGraph for saving as JSON */
		var interString, allMapsString;

		// n.b. since Dictionary / List does not have an interpretable asCompileString result (they do not recursivery replace contents of custom classes with their respective compile strings), we have to make it here...

		allMapsString = "Array.newFrom([";
		this.allMaps.values.do({|map, idx|
			if (idx == 0, {
				allMapsString = allMapsString + map.asCompileString;
			}, {
				allMapsString = allMapsString + ", " + map.asCompileString;
			})
		});
		allMapsString = allMapsString + "])";
		interString = "TopNodeGraph.new(" + allMapsString + ")";
		^interString;
	}

	asCompileStringSimple {
		/* representation of TopNodeGraph for saving as JSON, with reduced info (no intermediate Nodes)
		- TODO: move both SAVE methods to a more reasonable name (i.e. getSave, getSaveSimple).
		*/
		var interString, allMapsString;

		// n.b. since Dictionary / List does not have an interpretable asCompileString result (they do not recursivery replace contents of custom classes with their respective compile strings), we have to make it here...

		allMapsString = "Array.newFrom([";
		this.allMaps.values.do({|map, idx|
			if (idx == 0, {
				allMapsString = allMapsString + map.asCompileStringSimple;
			}, {
				allMapsString = allMapsString + ", " + map.asCompileStringSimple;
			})
		});
		allMapsString = allMapsString + "])";
		interString = "TopNodeGraph.new(" + allMapsString + ")";
		^interString;
	}
}



TopNode {
	/*
	Holder of contents in TopNodeMap / TopNodeGraph. Functions:
	- holds PolyContents.
	- applies genFunc(s) and combFunc(s) to produce children in TopNodeMap
	- atm, does not know about surrounding nodes.
	*/
	var
	<>contents = nil, // PolyContents
	<>relCoords = nil, // int[phi, l]. Vector coordinates relative to the origin cell;
	<>id = nil; // int id, uniquely generated by client. n.b. this implementation means that node IDs can't be generated at both sides - only in client.

	*new {|contents, relCoords|
		/*
		@params: PolyContents contents, int[phi, l] coords, int id;
		*/
		^super.new.init(contents, relCoords);
	}

	init {|contents, relCoords|
		var tempPoint;

		if (contents.class.superclass != PolyContents, { Error("TopNode.init(): contents superclass not of type PolyContents").throw });
		if ((relCoords.class != Array), { Error("TopNode.init(): rel coords not of type array").throw });
		// if ((relCoords[0] < 0) || (relCoords[1] < 0), { Error("TopNode:init(): relCoords can't be negative'").throw;} ); // DEPRECATED, relCoords [-pi, pi]
		// TODO id check

		this.id = id; // unique integer ID a node.

		this.contents = contents;
		this.relCoords = relCoords;
	}

	setID {|id|
		this.id = id;
	}

	duplicate {
		var dupNode;
		dupNode = TopNode.new(this.contents.duplicate(), this.relCoords);
		^dupNode;
	}

	getNode {|genAlg|
		/* makes new Node from current Node */
		var newNode, newContents, newRelCoords;
		newContents = genAlg.(this.contents);
		newRelCoords = this.relCoords + [0,1];
		newNode = TopNode.new(newContents, newRelCoords, id); // make new Node
		^newNode;
	}

	getContents {
		^this.contents;
	}

	asCompileString {
		/* representation of TopAlgs for saving as JSON */
		var interString;
		interString = "TopNode.new(" + this.contents.asCompileString + ", " + this.relCoords.asCompileString + ").setID(" + this.id + ")";
		^interString;
	}
}

TopAlgs {
	/* holds genAlgs, noiseAlgs, combAlgs in a dictionary. Functions:
	- syncs with clients GUI on each update of genAlgs
	*/
	var
	<>genAlgs = nil, // list of algorithm names that should be displayed in alg FAB in gui
	<>combAlgs = nil,
	<>topBridge = nil;

	*new {
		/*
		@params:
		*/
		^super.new.init();
	}

	init {
		this.genAlgs = Dictionary.new(6);
		this.combAlgs = Dictionary.new(6);
		this.initDefaultAlgs();
	}

	setTopBridge {|topBridge|
		/* used in up-communication to TopBridge --> Client
		i.e. update client GUI when selectable algorithm list is updated
		*/
		this.topBridge = topBridge;
	}

	initDefaultAlgs {
		var defaultGenAlg, defaultCombAlg;
		/*
		genAlg: PolyContents --genAlg--> PolyContents
		combAlg: PolyContents[] --combAlg + weights--> PolyContents, time (nIterations) can be tracked via global variable. Good for interpolating, combining, mixing (param/voice) states.
		TODO implement time when (if) equivalent radial graph is available for comb'd nodes
		*/
		/*nullGen = {|polyContents|
		/* NOT USED, for reference only!
		-template for transformational alg, does not transform anything */

		var defName, paramNames, pppv,
		nVoices, nParams;

		// Get all the insides of PolyContents
		defName = polyContents.defName;
		paramNames = polyContents.paramNames;
		pppv = polyContents.pppv;
		nParams = pppv.rows;
		nVoices = pppv.cols;

		// Make a transformation on pppv
		pppv = pppv.asArray; // 1D array
		// <<< transform >>>
		pppv = Array2D.fromArray(nParams, nVoices, pppv); // revert to Array2D

		// Create new PolyContents
		polyContents = PolyState(defName, paramNames, pppv);
		polyContents;
		};*/

		/*defaultGenAlg = {|polyContents|
		/* randomly walk all values up/down for all non-static parameters by % step
		@params: PolyContents contents
		@return: PolyContents contents
		*/
		var defName, paramNames, pppv, behavior, trans,
		nVoices, nParams, stepSize = 0.5 / 100;

		// Get all the insides of old PolyContents
		defName = polyContents.defName;
		paramNames = polyContents.paramNames;
		pppv = polyContents.pppv.deepCopy; // Array2D is mutable!

		nParams = pppv.rows; // each row is different param
		nVoices = pppv.cols; // each colums is different voice

		// Create new PolyContents
		if (polyContents.class == PolySO, {
		behavior = polyContents.behavior.collect({|strandBehavior| strandBehavior.duplicate });
		behavior.do(
		{|strandBehavior|
		var newEnvXYC;
		newEnvXYC = strandBehavior.envXYC;

		// transform behavior
		strandBehavior.envXYC = newEnvXYC;
		});
		polyContents = PolySO(defName, paramNames, pppv, behavior);

		});

		if (polyContents.class == PolyState, {
		polyContents = PolyState(defName, paramNames, pppv);

		});

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
		polyContents;
		};*/

		/*defaultCombAlg = {|targetContents, coefs|
		/*  multiple contents are combined into one state using combAlg
		@params: PolyContents[cont1, cont2, cont3...], coeffs
		@return: Polycontents singleContents
		*/
		var defName, paramNames, pppv, adjustedVals, pppvCombed, pppvCombedFlat,
		nVoices, nParams, newContents;

		defName = targetContents[0].defName;
		paramNames = targetContents[0].paramNames;

		pppv = targetContents[0].pppv;
		nParams = pppv.rows;
		nVoices = pppv.cols;

		// ("coefs: " + coefs).postln; // DEBUG
		// adjust contents to coefficient
		adjustedVals = targetContents.collect({|cont, idx|
		cont.pppv.asArray * coefs[idx];
		});
		// ("coef'd vals: " + adjustedVals).postln; // DEBUG

		// average flat content array
		pppvCombedFlat = adjustedVals.reduce({|valsA, valsB| valsA + valsB }); // n.b. coefficients already include division
		// ("average array: " + pppvCombedFlat).postln; // DEBUG

		pppvCombed = Array2D.fromArray(nParams, nVoices, pppvCombedFlat);

		newContents = PolyState.new(defName, paramNames, pppvCombed);
		// ("new contents: ").postln; // DEBUG
		// newContents.dump;
		newContents

		};*/

		/*this.genAlgs.put('random walk (0.5%)', defaultGenAlg);
		this.combAlgs.put('interpolate (lin)', defaultCombAlg);*/
		("TopAlgs: default algorithms are deprecated and disabled. Manually add algorithms.").warn;

		("TopAlgs: default algorithms initialized.").postln;
	}

	sync {
		/* syncs selectable algs from client GUI
		*/
		var syncTopAlgsString, file;
		JSON; // TODO: remove dependency. TopBridge requires JSON.sc from API quark

		r {
			0.25.wait(); // hack - wait for (longish) topSync to be processed
			syncTopAlgsString = JSON.stringify(  this );

			file = File("~/Desktop/TopClassesv04_alg_sync_debug.txt".standardizePath,"w"); // DEBUG
			file.write(syncTopAlgsString);// DEBUG
			file.close; // DEBUG

			("TopAlgs.sync(): Sync for TopAlgs sent! clientReceiverAddr - " + this.topBridge.clientReceiverAddr + "; " + "message - " + syncTopAlgsString).postln; // DEBUG
			this.topBridge.clientReceiverAddr.sendMsg(this.topBridge.topAlgsSync, "" ++ syncTopAlgsString);
		}.play;
	}

	// Data structures
	/////////////////

	setGuiAlgs {|genAlgs, combAlgs|
		/* sets algorithms in client alg GUI, if not a duplicate
		*/
		this.genAlgs = genAlgs;
		this.combAlgs = combAlgs;
		this.sync();
	}

	/*get {|name|
	/* getter for algs selected by name
	*/
	^this.algsDict.at(name.asSymbol);
	}*/

	/*edit {|name|
	/* gets alg as code for editing */
	^this.algsDict.at(name).asCompileString();
	}*/

	toSyncJSON {
		/* representation of TopAlgs for sending to PolyTop Client as JSON. Used in TopAlgs sync string
		*/

		var compileString, genAlgNames, combAlgNames;
		compileString = Dictionary.new();

		genAlgNames = this.genAlgs.keys.asArray;
		combAlgNames = this.combAlgs.keys.asArray;

		compileString.put(\genAlgs, genAlgNames);
		compileString.put(\combAlgs, combAlgNames);

		^JSON.stringify(compileString);
	}

	asCompileString {
		/* representation of TopAlgs for saving as JSON */
		Error("TopAlgs.asCompileString(): not implemented!").throw;
	}


}


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

TopBridge {
	/* Communication between the server, a client GUI and WFS_collider. Functions:
	- register client to server
	- playNode
	- drawNode
	- deleteNode
	- combNode
	- setAlgorithm

	- Server functions:
	- restore saved state of NodeGraph
	- communication to WFS_collider's UScore (i.e. place UMarker for event).

	- holds voices that need to have their state preserved in continuous type
	*/

	var
	<>clientIP = 0, // IP of android client, set at init

	<>clientPort = 12396, // client listens for sync on this port, constant for all clients
	<>clientSendPort = 0, // client sends from this port. Dynamic, set via /topSyncReply
	<>receivingPort = 12395, // server listens for messages, constant for this server
	<>clientReceiverAddr = nil, // NetAddr of client, sync destination
	<>clientSenderAddr = nil, // NetAddr of client, source of client messages


	// OSC message constants
	<>topSync = '/topSync', // server -> client
	<>topSyncReply = '/topSyncReply', // client -> server

	<>topSyncPart = '/topSyncPart', // server -> client (sync > 1500 bytes, sent in parts)
	<>topSyncPartEnd = '/topSyncPartEnd', // server -> client (sync > 1500 bytes, sent in parts)

	<>topAlgsSync = '/topAlgsSync', //server -> client, OSC sync for alg selection in client GUI

	<>topPlay = '/topPlay', // client -> server, plays selected node
	<>topComb = '/topComb', // client -> server, plays selected node
	<>topAdd = '/topAdd', // client -> server, adds new TopNodeMap with selected node copy at the center
	<>topDelete= '/topDelete', // client -> server, deletes new TopNodeMap

	<>topSendToWFS = '/topSendToWFS',

	<>topSetGenAlg = '/topSetGenAlg', // OSC genAlg selection message
	<>topSetCombAlg = '/topSetCombAlg', // OSC combAlg selection message


	// listeners
	<>listenerNames = nil, // array of unique OSC listener
	<>listopSyncReply = nil, // OSCFunc of /topSyncReply

	<>listopPlay = nil, // OSCFunc of /topPlay
	<>listopAdd = nil,// OSCFunc of /topAdd
	<>listopDelete = nil,// OSCFunc of /topDelete
	<>listopComb= nil, // OSCFunc of /topComb

	<>listopSendToWFS = nil,// OSCFunc of /topSendToWFS

	<>lisTopSetGenAlg = nil, // OSCFunc of /topSetGenAlg
	<>lisTopSetCombAlg = nil, // OSCFunc of /topSetCombAlg

	// other globals
	<>nodeGraph = nil, // TopNodeGraph nodeGraph

	<>topAlgs = nil, // TopAlgs object with all defined algorithms
	<>genAlg = nil, // currently selected genAlg;
	<>combAlg = nil, // currently selected combAlg;

	// WFS collider selections
	<>uscore = nil,
	<>scoreEditor = nil, // a gui for the uscore being edited
	<>gestTime = nil, // Array of gesture start times, indexed by wfsIdx

	// preserve voice state
	<>voices = nil, // Dictionary of voices (multi-synth array, result of playing PolyContents in continuous voice type). TYPE_CONTINUOUS its up to 10 voices, in RING_MODE its infinite.
	<>behaviors = nil, // Dictionary of behaviors (multi-synth array, result of playing PolySO in behavior voice type). TYPE_CONTINUOUS its up to 10 behaviors (for each voice), in RING_MODE its infinite.

	// preserve last played Node
	<>lastPlayedMap = nil;

	*new {|clientIP, nodeGraph, topAlgs|
		/* new
		@params: String clientIP, TopNodeGraph nodeGraph
		*/
		^super.new.init(clientIP, nodeGraph, topAlgs);
	}

	*closeAllBridges {
		/* closes all bridges that have been opened since SC boot
		TODO: likely does not close all OSC listeners reliably...
		*/
		if ((~topBridgeBank == nil),
			{
				"TopBridge.closeAllBridges(): ~topBridgeBank is nil, no bridges to close".warn;
			},
			{
				~topBridgeBank.do({ |bridge, idx|
					if (bridge.class == TopBridge,
						{ bridge.closeBridge();
							("TopBridge.closeAllBridges(): Bridge " + idx + " closed.").postln;
						},
						{ Error("TopBridge.closeAllBridges(): Can't close bridge, something went wrong!").throw; }
					);
				})
		});

		~topBridgeBank = nil;
		"-> TopBridge.closeAllBridges: all bridges are closed".postln;
	}

	init {|clientIP, nodeGraph, topAlgs|
		//Error checking
		// ("TopBridge.init(): client IP argument " +clientIP + ", class " + clientIP.class).postln; // DEBUG
		if ((this.isValidIP(clientIP)).not, { Error("TopBridge.init(): client IP not valid").throw });
		if (nodeGraph.class != TopNodeGraph, { Error("TopBridge.init(): map not of class TopNodeGraph").throw });
		if (topAlgs.class != TopAlgs, { Error("TopBridge.init(): algs object not of class TopAlgs").throw });

		this.clientIP = clientIP;
		this.clientReceiverAddr = NetAddr(clientIP, clientPort);
		this.nodeGraph = nodeGraph;

		this.topAlgs = topAlgs;
		this.topAlgs.setTopBridge(this);
		this.topAlgs.setGuiAlgs(this.topAlgs.genAlgs, this.topAlgs.combAlgs); // default algs are sent to client
		this.genAlg = this.topAlgs.genAlgs.at('random walk (0.5%)'); // default genAlg is set
		this.combAlg = this.topAlgs.combAlgs.at('interpolate (lin)'); // default combAlg is set

		this.syncClient();

		this.voices = Dictionary.new(10); // a dictionary of synths that are currently playing
		this.behaviors = Dictionary.newFrom([0, nil, 1, nil, 2, nil, 3, nil, 4, nil, 5, nil, 6, nil, 7, nil, 8, nil, 9, nil]); // a dictionary of behaviors that are currently playing

		this.gestTime = Dictionary.new(100); // a dictionary of start times for each gesture currently recording

		// make unique OSCdef names (n.b. uniqueness not used now)
		this.listenerNames = Array.newFrom([
			\combAlgsLis.asString ++ this.identityHash,
			\playLis.asString ++ this.identityHash,
			\combLis.asString ++ this.identityHash,
			\wfsLis.asString ++ this.identityHash,
			\addLis.asString ++ this.identityHash,
			\delLis.asString ++ this.identityHash,
			\genAlgLis.asString ++ this.identityHash,
			\syncLis.asString ++ this.identityHash,
		]);

		/* This solution was temp and obsolete sine 101017
		r {
		this.initListopSyncReply();

		2.wait; // delay until client replies to sync and its port number is known // TODO: find another solution. This number is getting bigger with more nodes!
		this.clientSenderAddr = NetAddr(clientIP, clientSendPort);

		this.initListopPlay();
		this.initListopComb();
		this.initListopAdd();
		this.initListopDelete();
		this.initListopSendToWFS();

		// this.initLisTopSetGenAlg();
		// this.initLisTopSetCombAlg();
		}.play;*/

		this.initListopSyncReply();

		// The rest of the listeners are initialized in lisTopSyncReply(), once the sync reply arrives from client

		// Add to topBridge bank, for closing lost references
		if (~topBridgeBank == nil,
			{
				~topBridgeBank = List.new();
				~topBridgeBank.add(this);
			},
			{ ~topBridgeBank.add(this); }
		);

		~polyTopWFSVoices = Dictionary.new();
		~wfsIdx = 0;
	}

	/*
	backup
	*initDefault {|defName, nVoices, ip|
	/* convenience method to initialize TopBridge from SynthDef and nVoices. Creates all internal objects - PolyState, TopNodeMap, TopNodeGraph, TopBridge.
	@params: String defName, int nVoices, String ip (of PolyTop client)
	@return: Array[TopBridge, TopNodeGraph, TopAlgs, state]. For explicit use.
	*/
	var state, cNodeGraph, topAlgs, topBridge;

	// TODO: error checking on the inputs!

	"*** ----> Initializing a PolyState...".postln;
	state = PolyState.newDefault(defName, nVoices);
	state.printContents;

	"*** ----> Initializing TopNodeGraph...".postln;
	cNodeGraph = TopNodeGraph.new();

	"".postln;
	"*** ----> Adding state as origin to TopNodeGraph...".postln;
	cNodeGraph.addOrigin(state, [0, 0]);

	"".postln;
	"*** ----> Initializing TopAlgs...".postln;
	topAlgs = TopAlgs.new();

	"".postln;
	"*** ----> Initializing TopBridge...".postln;
	topBridge = TopBridge.new(ip, cNodeGraph, topAlgs);
	^[topBridge, cNodeGraph, topAlgs, state]
	}*/

	*initDefault {|defName, nVoices, ip, type = \beh|
		/* convenience method to initialize TopBridge from SynthDef and nVoices. Creates all internal objects - PolyState, TopNodeMap, TopNodeGraph, TopBridge.
		@params: String defName, int nVoices, String ip (of PolyTop client)
		@return: Array[TopBridge, TopNodeGraph, TopAlgs, state]. For explicit use.
		*/
		var state, cNodeGraph, topAlgs, topBridge;

		// TODO: error checking on the inputs!

		"*** ----> Initializing a PolyState...".postln;
		if (type == \beh,
			{
				state = PolySO.newDefault(defName, nVoices);
			},
			{
				state = PolyState.newDefault(defName, nVoices);
			}
		);

		state.printContents;

		"*** ----> Initializing TopNodeGraph...".postln;
		cNodeGraph = TopNodeGraph.new();

		"".postln;
		"*** ----> Adding state as origin to TopNodeGraph...".postln;
		cNodeGraph.addOrigin(state, [0, 0]);

		"".postln;
		"*** ----> Initializing TopAlgs...".postln;
		topAlgs = TopAlgs.new();

		"".postln;
		"*** ----> Initializing TopBridge...".postln;
		topBridge = TopBridge.new(ip, cNodeGraph, topAlgs);
		^[topBridge, cNodeGraph, topAlgs, state]
	}

	syncClient {
		/* Sends node map data to client to make GUI. Closes and reopens bridge before syncing
		*/
		var syncValues, f, syncString, nodeIDList, nodeXList, nodeYList, file; // Dictionary // DEBUG - delete "f"

		JSON; // TODO: remove dependency. TopBridge requires JSON.sc from API quark.
		syncString = JSON.stringify( nodeGraph.allMaps.values );

		file = File("~/Desktop/TopClassesv04_sync_debug.txt".standardizePath,"w"); // DEBUG
		file.write(syncString);// DEBUG
		file.close; // DEBUG

		this.sendSyncFragments(syncString, 500);
	}

	sendSyncFragments { |msg, maxLength = 500|
		/* divides OSC string into UDP-compatible fragments (msg.size < 1500);
		Usage: divide syncString in UDP-compatible fragments
		@params: String msg, int size (>1), Function function[args: substring];
		TODO: current implementation introduces a delay between each sync part. Find another way to send OSC messages (i.e. TCP)
		*/
		var start, finish;

		if (msg.size < maxLength,
			{ this.clientReceiverAddr.sendMsg(topSync, "" ++ msg) }, // if msg fits size, send whole msg

			{ var nMsg = msg.size.div(maxLength) + 1; // if msg > size, divide into parts
				if (msg.size % maxLength == 0 , {nMsg = nMsg - 1});

				r { // start sync part sending with delays...
					nMsg.do{ |idx| // make each part
						var subMsg;

						start = (idx * maxLength);
						finish = maxLength + (idx * maxLength) - 1;

						if (start < finish, {
							subMsg = msg.copyRange(start, finish);
							// ("doForSubstrings: substring idx: " + idx + ", subtring: " + subMsg).postln; // DEBUG
							this.clientReceiverAddr.sendMsg(this.topSyncPart, "" ++ subMsg); // send part to client
							((1 / 1000) * 100).wait; // delay to avoid concurrency bug at client
						});
					};
					this.clientReceiverAddr.sendMsg(this.topSyncPartEnd, ""); // signal end of sync
				}.play;
		});
	}


	// TODO: change all OSCFuncs to defs to exclude them from CMD.
	initListopSyncReply {
		/* Defines and initializes a /topSyncReply listener
		Sync reply is OSC message that client sends once sync is received to indicate its port number
		*/
		if ( this.listopSyncReply == nil, {
			this.listopSyncReply = OSCdef(this.listenerNames[7],
				{
					|msg, time, addr, port|
					this.clientSendPort = addr.port;
					"initListopSyncReply(): client received sync successfully!".postln;

					this.clientSenderAddr = NetAddr(this.clientIP, this.clientSendPort);

					this.initListopPlay();
					this.initListopComb();
					this.initListopAdd();
					this.initListopDelete();
					this.initListopSendToWFS();
				},
				path: this.topSyncReply,
				recvPort: this.receivingPort;
			);
		}, { "listopSyncReply(): this.listopSyncReply is not nil, reusing...".postln; });
	}

	/*initLisTopSetGenAlg {
	/* deprecated since algs are sent with /topPlay and /topComb messages */

	/* Defines and initializes a /topSetGenAlg
	/topSetGenAlg arguments:
	- String name, a name of algorithm that was selected.
	*/

	this.lisTopSetGenAlg = OSCdef(this.listenerNames[6], // n.b. OSCdef names are unique, tied to each TopBridge (for multi-bridge setups)
	{
	|msg, time, addr, port|
	var parsedMsg;

	// parse incoming msg
	parsedMsg = this.parsetopSetGenAlg(msg);
	("TopAlgs.topSetGenAlg(): algorithm selected " ++ parsedMsg[0]).postln; // DEBUG
	this.genAlg = this.topAlgs.genAlgs.at(parsedMsg[0].asSymbol);
	},
	path: this.topSetGenAlg,
	srcID: this.clientSenderAddr,
	recvPort: this.receivingPort;
	);
	}

	initLisTopSetCombAlg {
	/* deprecated since algs are sent with /topPlay and /topComb messages */

	/* Defines and initializes a /topSetComb`Alg
	/topSetCombAlg arguments:
	- String name, a name of algorithm that was selected.
	*/

	this.lisTopSetCombAlg = OSCdef(this.listenerNames[0],
	{
	|msg, time, addr, port|
	var parsedMsg;

	// parse incoming msg
	parsedMsg = this.parsetopSetGenAlg(msg); // we use the same checker as genAlg, since same syntax
	("TopAlgs.topSetCombAlg(): algorithm selected " ++ parsedMsg[0]).postln; // DEBUG
	this.combAlg = this.topAlgs.combAlgs.at(parsedMsg[0].asSymbol);
	},
	path: this.topSetCombAlg,
	srcID: this.clientSenderAddr,
	recvPort: this.receivingPort;
	);
	}

	parsetopSetGenAlg{|msg|
	/* deprecated since algs are sent with /topPlay and /topComb messages */

	/* try to parse /topSetGenAlg, /topSetCombAlg messages
	@params: OSCmessage msg (Array[String path, String msgArray])
	@return: parsedMsg[String algName]
	*/
	var msgString, msgArgs, parsedMsg;

	// Error checking
	{ var name;
	msgString = msg[1].asString;
	msgArgs = msgString.split($,);

	msgArgs[0]; // "PolyTop", discarded header of message,
	name = msgArgs[1].asString[1..]; // alg name, n.b. no spacebar
	parsedMsg = [name];
	}.try({ Error("TopBridge.parsetopSetGenAlg(): can't parse /topSetGenAlg or /topSetCombAlg message: " +msg).throw });
	^parsedMsg;
	}*/

	initListopPlay {
		/* Defines and initializes a topPlay listener
		/topPlay arguments:
		- int id.
		- int phi, int l.
		*/
		this.listopPlay = OSCdef(this.listenerNames[1],
			{
				|msg, time, addr, port|
				var parsedMsg, playedNode;

				parsedMsg = this.parsetopPlayMessages(msg);
				("TopBridge.topPlay: PolyTop message " + parsedMsg).postln; // DEBUG

				this.genAlg =  this.topAlgs.genAlgs.at(parsedMsg.at(\genAlg).asSymbol);
				if (this.genAlg == nil, {
					Error.new("initLisTopPlay(): received genAlg is nil, skipping message...").throw;
					^nil
				});

				playedNode = nodeGraph.getNodeMap(parsedMsg.at(\nodeID));
				if (playedNode == nil, {
					Error.new("listopPlay(): played Node does not exist").throw;
					^nil;
				});

				// Gesture recording
				~wfsIdx = 0; // TODO: reserve 1 track for each gesture
				if (parsedMsg.at(\recGest) == 1, { // gesture is recording... (pressed == 1, recGest = 1)
					// register gesture start
					if ((this.gestTime[~wfsIdx] == 0) || (this.gestTime[~wfsIdx] == nil), {
						this.gestTime[~wfsIdx] = SystemClock.seconds;
					});

					// store playable in UScore
					this.addPlayableToWFS(
						playedNode.getNodePolar([parsedMsg.at(\phi), parsedMsg.at(\l)], genAlg),
						parsedMsg.at(\type),
						parsedMsg.at(\pressed),
						~wfsIdx,
						SystemClock.seconds - this.gestTime[~wfsIdx]);
				});
				if (parsedMsg.at(\recGest) == 2, // recording is stopping - gesture end playable is added... (pressed == 0, recGest = 2)
					{
						// store playable in UScore
						this.addPlayableToWFS(
							playedNode.getNodePolar([parsedMsg.at(\phi), parsedMsg.at(\l)], genAlg),
							parsedMsg.at(\type),
							parsedMsg.at(\pressed),
							~wfsIdx,
							SystemClock.seconds - this.gestTime[~wfsIdx]);

						this.gestTime[~wfsIdx] = 0;
				});

				if (parsedMsg.at(\recGest) == 0, { /* continue normally, recording is off... (pressed == 1 or 0, recGest = 0) */ });

				this.lastPlayedMap = playedNode;
				if (parsedMsg.at(\type) == 0, { // discrete event
					// ("TopBridge.lisTopPlay(): discrete playback event").postln; // DEBUG

					// play TopNode discrete
					playedNode
					.getNodePolar([parsedMsg.at(\phi), parsedMsg.at(\l)], genAlg)
					.getContents()
					.playDiscrete();
				});
				if (parsedMsg.at(\type) == 1, { // continuous event
					var voice;
					// ("TopBridge.lisTopPlay(): continuous playback event").postln; // DEBUG
					// play TopNode continuous
					voice = playedNode
					.getNodePolar([parsedMsg.at(\phi), parsedMsg.at(\l)], genAlg)
					.getContents()
					.playContinuous(this.voices.at(parsedMsg.at(\idx)));

					this.voices.put(parsedMsg.at(\idx), voice);
				});

				if (parsedMsg.at(\type) == 2, { // behavior event
					var voiceAndBehavior, voice, behavior;
					// ("TopBridge.lisTopPlay(): behavior event").postln; // DEBUG
					// play TopNode behavior
					voiceAndBehavior = playedNode
					.getNodePolar([parsedMsg.at(\phi), parsedMsg.at(\l)], genAlg)
					.getContents()
					.playBehavior(this.voices.at(parsedMsg.at(\idx)), this.behaviors.at(parsedMsg.at(\idx)));

					this.voices.put(parsedMsg.at(\idx), voiceAndBehavior[0]);
					this.behaviors.put(parsedMsg.at(\idx), voiceAndBehavior[1]);

				});

				// check if node released
				if (parsedMsg.at(\pressed) == 0,
					{
						var voice, behavior;
						// ("TopBridge.topPlay(): voice " + parsedMsg.at(\idx) + " released").postln; // DEBUG

						// stop behavior first
						behavior = this.behaviors.at(parsedMsg.at(\idx));
						if (behavior != nil,
							{
								("behavior for voice " + parsedMsg.at(\idx) + " " + behaviors.at(parsedMsg.at(\idx))).postln;
								this.behaviors.at(parsedMsg.at(\idx)).do({|behavior| behavior.stop});
								this.behaviors.put(parsedMsg.at(\idx), nil);
							}
						);

						// stop voices after behavior is stopped
						voice = this.voices.at(parsedMsg.at(\idx));
						/* begins content release, by setting its \t_pressed arg to 0 */
						if (voice != nil, {
							voice.do({|synth|
								synth.set(\t_released, 1);
							});
							this.voices.put(parsedMsg.at(\idx), nil);
						});

				});
			},
			path: topPlay,
			srcID: clientSenderAddr,
			recvPort: receivingPort;
		);
	}

	initListopComb {
		/* Defines and initializes a topComb listener
		/topComb arguments:
		- int id.
		- int x, y (n.b. these are absolute coordinates!)
		- int[] comb target IDs // TODO: implement
		*/
		this.listopComb = OSCdef(this.listenerNames[2],
			{
				|msg, time, addr, port|
				var parsedMsg, lastDef, combNode, combTargets;

				parsedMsg = this.parsetopCombMessages(msg);
				("TopBridge.topComb: PolyTop message " + parsedMsg).postln; // DEBUG

				this.combAlg = this.topAlgs.combAlgs.at(parsedMsg.at(\combAlg).asSymbol);
				if (this.combAlg == nil, { // todo: changed from genAlg, because assumed mistake? check...
					Error.new("initLisTopComb(): received combAlg is nil, skipping message...").throw;
					^nil
				});

				combTargets = parsedMsg.at(\combTargets);

				// ("listTopComb: combTargets - " + combTargets).postln; // DEBUG
				if (this.lastPlayedMap != nil,
					{
						("DEBUG: last def set").postln;
						lastDef = this.lastPlayedMap.origin.getContents.defName
					}, { lastDef = \nonExistingSynthDef;  }
				);
				if (combTargets[0] == "all",
					{ combNode = this.nodeGraph.combineNodes(parsedMsg.at(\x), parsedMsg.at(\y), this.nodeGraph.getNodesBySynthDef( lastDef ), combAlg) }, // TODO implement synthdef selection
					{
						("DEBUG: combNode created from combineNodes() with getNodesById() targets").postln;
						combNode = this.nodeGraph.combineNodes(parsedMsg.at(\x), parsedMsg.at(\y), this.nodeGraph.getNodesById(combTargets), combAlg) }
				);

				// Gesture recording
				// ~wfsIdx is initialized as 0 at TopBridge.init();
				if (parsedMsg.at(\recGest) == 1, { // gesture is recording... (pressed == 1, recGest = 1)
					// register gesture start
					if ((this.gestTime[~wfsIdx] == 0) || (this.gestTime[~wfsIdx] == nil), {
						this.gestTime[~wfsIdx] = SystemClock.seconds;
					});

					// store playable in UScore
					this.addPlayableToWFS(
						combNode,
						parsedMsg.at(\type),
						parsedMsg.at(\pressed),
						~wfsIdx,
						SystemClock.seconds - this.gestTime[~wfsIdx]);
				});
				if (parsedMsg.at(\recGest) == 2, // recording is stopping - gesture end playable is added... (pressed == 0, recGest = 2)
					{
						// store playable in UScore
						this.addPlayableToWFS(
							combNode,
							parsedMsg.at(\type),
							parsedMsg.at(\pressed),
							~wfsIdx,
							SystemClock.seconds - this.gestTime[~wfsIdx]);
						this.gestTime[~wfsIdx] = 0;

						~wfsIdx = ~wfsIdx + 1; // at the end of gesture, voices idx is incremented
				});

				if (parsedMsg.at(\recGest) == 0, { /* continue normally, recording is off... (pressed == 1 or 0, recGest = 0) */ });



				if (parsedMsg.at(\type) == 0, {
					// ("TopBridge.lisTopComb(): discrete playback event").postln; // DEBUG

					// comb TopNode discrete
					combNode.getContents.playDiscrete();
				}, {
					var voice;
					// ("TopBridge.lisTopComb(): continuous playback event").postln; // DEBUG
					// play TopNode continuous
					voice = combNode.getContents.playContinuous(this.voices.at(parsedMsg.at(\idx)));

					this.voices.put(parsedMsg.at(\idx), voice);

					// check if node released
					if (parsedMsg.at(\pressed) == 0, {
						var voice;
						// ("TopBridge.topPlay(): voice " + parsedMsg.at(\idx) + " released").postln; // DEBUG
						voice = this.voices.at(parsedMsg.at(\idx));
						/* begins content release, by setting its \t_pressed arg to 0 */
						if (voice != nil, {
							voice.do({|synth|
								synth.set(\t_released, 1);
							});
							this.voices.put(parsedMsg.at(\idx), nil);
						});
					});
				});
			},
			path: topComb,
			srcID: clientSenderAddr,
			recvPort: receivingPort;
		);
	}


	initListopSendToWFS {
		/* Defines and initializes a topSendToWFS listener
		/topSendToWFS arguments:
		- int id.
		TODO: currently disabled
		*/
		this.listopSendToWFS = OSCdef(this.listenerNames[3],
			{
				|msg, time, addr, port|
				var parsedMsg, uPolyState, node, scoreEditor;
				var action, uMarker;

				// parse incoming msg
				parsedMsg = this.parsetopSendToWFSMessages(msg); // ...only ID is parsed
				("TopBridge.topSendToWFS: PolyTop message " + parsedMsg).postln; // DEBUG

				// make sure that a UScore is available
				if (UScore.current == nil, {
					this.uscore = UScore.new();
				},
				{
					this.uscore = UScore.current;
				});

				node = this.nodeGraph.getNodeMap(parsedMsg[0]).origin;

				/* TODO, uncomment once UPolyState is complete
				// make UPolyState from TopNode
				uPolyState = UPolyState.new(node);
				uPolyState.startTime = this.uscore.pos;
				uPolyState.track = this.uscore.findCompletelyEmptyTrack();*/

				action = {
					node.getContents().playDiscrete(); // TODO
				};

				uMarker = UMarker.new( this.uscore.pos, this.uscore.findCompletelyEmptyTrack(), node.id, action );

				{
					if (this.scoreEditor == nil, {
						this.scoreEditor = this.uscore.gui.editor;
					});
					// scoreEditor.changeScore( { this.uscore.addEventToEmptyTrack(uPolyState) } );
					this.scoreEditor.changeScore( { this.uscore.addEventToEmptyTrack(uMarker) } );
					this.scoreEditor.score.changed(\numEventsChanged);
				}.defer;
			},
			path: topSendToWFS,
			srcID: clientSenderAddr,
			recvPort: receivingPort;
		);
	}

	addPlayableToWFS {|node, voiceType, pressed, wfsIdx, time|
		/* adds a marker to WFS score for a single OSC message (i.e. lisTopPlay, lisTopComb)
		- user is responsible for taking care of meaningful order and voice killing (i.e. by keeping \pressed 0 messages at the end of \pressed 1 sequence.

		@params:
		- TopNode node;
		- int voiceType;
		- int pressed; signals continuous voice release.
		- int wfsIdx; index inside ~polyTopWFSVoices. Matches track number
		- float time; (s), time == 0 at the beginning of gesture. uscore.pos + time --> playable position in UScore
		*/
		var action, uMarker;

		// make sure that a UScore is available
		if (UScore.current == nil, {
			this.uscore = UScore.new();
		},
		{
			this.uscore = UScore.current;
		});

		/* TODO, uncomment once UPolyState is complete
		// make U~polyTopWFSVoicesPolyState from TopNode
		uPolyState = UPolyState.new(node);
		uPolyState.startTime = this.uscore.pos;
		uPolyState.track = this.uscore.findCompletelyEmptyTrack();*/

		if (voiceType == 0, { // discrete
			action = {
				("addPlayableToWFS(): node: " + node + ", voice type " + voiceType + ", pressed " + pressed + ", wfsIdx " + wfsIdx + ", time" + time).postln; // DEBUG
				node.getContents().playDiscrete();
			};
		},
		{ // continuous
			if (pressed == 1,
				{
					action = {
						var voice;
						("addPlayableToWFS(): node: " + node + ", voice type " + voiceType + ", pressed " + pressed + ", wfsIdx " + wfsIdx + ", time" + time).postln; // DEBUG
						voice = ~polyTopWFSVoices.at(wfsIdx);
						voice = node.getContents.playContinuous(voice);
						~polyTopWFSVoices.put(wfsIdx, voice);
					};
				},
				{ // if released
					action = {
						var voice;
						("addPlayableToWFS(): node: " + node + ", voice type " + voiceType + ", pressed " + pressed + ", wfsIdx " + wfsIdx + ", time" + time).postln; // DEBUG
						// ("TopBridge.topPlay(): voice " + parsedMsg.at(\idx) + " released").postln; // DEBUG
						voice = ~polyTopWFSVoices.at(wfsIdx);
						/* begins content release, by setting its \t_pressed arg to 0 */
						if (voice != nil,
							{
								voice.do({|synth|
									synth.set(\t_released, 1);
								});
								~polyTopWFSVoices.put(wfsIdx, nil);
						});
					}
			});
		});

		uMarker = UMarker.new( this.uscore.pos + time, wfsIdx, node.id, action );

		{
			if (this.scoreEditor == nil, {
				this.scoreEditor = this.uscore.gui.editor;
			});
			// scoreEditor.changeScore( { this.uscore.addEventToEmptyTrack(uPolyState) } );
			this.scoreEditor.changeScore( { this.uscore.addEventsToEmptyRegion([uMarker]) } );
			this.scoreEditor.score.changed(\numEventsChanged);
		}.defer;
	}

	initListopAdd {
		/* Defines and initializes a topAdd listener
		/topAdd arguments:
		- origin node ID.
		- relative phi, l
		- absolute x, y

		// TODO: arguments are out of date

		// Shared between GEN_MODE and COMB_MODE.
		*/
		this.listopAdd = OSCdef(this.listenerNames[4],
			{
				|msg, time, addr, port|
				var topAddMessage, selectedMap, selectedNode, newMap, parentNode, linkerCoords;

				// parse incoming msg to dictionary
				topAddMessage = this.parsetopAddMessages(msg);

				("TopBridge.topAdd: PolyTop message " + topAddMessage).postln; // DEBUG

				// get TopNodeMap which is selected
				if (topAddMessage.at(\combActive) == 0, { // adding a genAlg node...
					selectedMap = nodeGraph.getNodeMap(topAddMessage.at(\parentID));

					if (selectedMap == nil, { Error("TopBridge.initListopAdd: selectedMap == nil").throw } ); // DEBUG

					//get TopNode at rel coords of selected map
					selectedNode = selectedMap.getNodePolar([topAddMessage.at(\phi), topAddMessage.at(\l)], genAlg);
					// TODO implement setting the genAlg

					if (selectedNode == nil, { Error("TopBridge.initListopAdd: selectedNode == nil").throw } ); // DEBUG
					~selectedNode = selectedNode;
					linkerCoords = selectedNode.relCoords;
					//make a new map from a copy of retrieved node, add to graph
					newMap = TopNodeMap.new(
						selectedNode.duplicate().setID(topAddMessage.at(\originID)), // assign client-sent ID to new origin
						[topAddMessage.at(\x), topAddMessage.at(\y)],
						topAddMessage.at(\linkName)
					).setLinkerCoords(linkerCoords);


					// add inMaps and outMaps
					// ("TopBridge.listopAdd(): parent node ID - " + topAddMessage.at(\inlinkIDs)[0]).postln; // DEBUG
					if (topAddMessage.at(\inlinkIDs)[0] != nil, { // first inLink ID - id of parent TopNodeMap
						newMap.inMaps = topAddMessage.at(\inlinkIDs);
					});

					// update selected map out-nodes
					selectedMap.outMaps.add(newMap.id);

					nodeGraph.addNodeMap(newMap);
					this.lastPlayedMap = newMap;
				},
				{ // adding a combAlg node... // TODO: rewrite this section to not duplicate code from initLisTopComb
					var parsedMsg, playedNode, combNode, combTargets, newMap;

					parsedMsg = this.parsetopAddMessages(msg);
					("TopBridge.initLisTopAdd: PolyTop message " + parsedMsg).postln; // DEBUG

					playedNode = nodeGraph.getNodeMap(parsedMsg.at(\nodeID));
					combTargets = parsedMsg.at(\combTargets);

					// ("listTopComb: combTargets - " + combTargets).postln; // DEBUG
					if (combTargets[0] == "all",
						{ combNode = this.nodeGraph.combineNodes(parsedMsg.at(\x), parsedMsg.at(\y), this.nodeGraph.getNodesBySynthDef(this.lastPlayedMap.origin.getContents.defName), combAlg) }, // TODO implement synthdef selection
						{ combNode = this.nodeGraph.combineNodes(parsedMsg.at(\x), parsedMsg.at(\y),  this.nodeGraph.getNodesById(combTargets), combAlg) }
					);
					// add combNode to graph
					newMap = TopNodeMap.new(
						combNode.duplicate.setID(topAddMessage.at(\originID)), // assign client-sent ID to new origin
						[topAddMessage.at(\x), topAddMessage.at(\y)],
						topAddMessage.at(\linkName)
					);
					this.nodeGraph.addNodeMap(newMap);
					this.lastPlayedMap = newMap;
				});
			},
			path: topAdd,
			srcID: clientSenderAddr,
			recvPort: receivingPort;
		);
	}

	initListopDelete {
		/* Defines and initializes a topDelete listener
		/topDelete arguments:
		- origin node ID.
		*/
		this.listopDelete = OSCdef(this.listenerNames[5],
			{
				|msg, time, addr, port|
				var parsedMsg, selectedMap, selectedMapInMaps;

				// parse incoming msg
				parsedMsg = this.parsetopDeleteMessages(msg);
				("TopBridge.topDelete: PolyTop message " + parsedMsg).postln; // DEBUG

				// delete selected TopNodeMap from NodeGraph and all references to it
				selectedMap = this.nodeGraph.allMaps
				.atFail(parsedMsg[0], {Error("Trying to delete TopNodeMap with ID, that is not in nodeGraph.allMaps dictionary!").throw });
				selectedMapInMaps = selectedMap.inMaps;
				selectedMapInMaps.do({|inMapID|
					var inMap;
					inMap = this.nodeGraph.getNodeMap(inMapID);
					if (inMap != nil, { inMap.outMaps.remove(selectedMap.id) });
				});
				this.nodeGraph.allMaps.removeAt(parsedMsg[0]);
			},
			path: topDelete,
			srcID: clientSenderAddr,
			recvPort: receivingPort;
		);
	}

	/* Error Checker methods for parsing JSONS */
	/////////////////////////////////////////////

	parsetopPlayMessages{|msg|
		/* try to parse /topPlay and /topPlayCont messages
		@params: OSCmessage msg (Array[String path, String msgArray])
		@return: parsedMsg[int id, phi, l]
		*/
		var msgString, msgArgs, parsedMsg;

		// Error checking
		{ var phi,l, id, type, idx, pressed, recGest, genAlg;
			msgString = msg[1].asString;
			msgArgs =  Dictionary.newFrom(JSON.parse(msgString)); // n.b. JSON.parse returns an Event

			// this parsing is only to catch errors, whole dictionary is returned instead
			id = msgArgs.at(\nodeID).asInteger; // origin ID
			phi = msgArgs.at(\phi).asFloat; // phi in map
			l = msgArgs.at(\l).asInteger; // l in map
			type = msgArgs.at(\type).asInteger; // discrete = 0, continuous = 1, ring = 2
			idx = msgArgs.at(\idx).asInteger; // pointer index (>10 in ring type)
			pressed = msgArgs.at(\pressed).asInteger; // pressed/released in continuous voice type
			recGest = msgArgs.at(\recGest).asInteger; // gesture
			genAlg = msgArgs.at(\genAlg); // name of the incoming link

		}.try({ Error("TopBridge.parsetopPlayMessages(): can't parse /topPlay message: " +msg).throw });
		^msgArgs;
	}

	parsetopCombMessages{|msg|
		/* try to parse /topComb and /topCombCont messages
		@params: OSCmessage msg (Array[String path, String msgArray])
		@return: parsedMsg[int id, phi, l, int[id, id, id...]]. If combTargets[0] == "all", then getNodesBySynthDef() called
		*/
		var msgString, msgArgs, parsedMsg;

		// Error checking
		{ var x, y, id, type, idx, pressed, combTargets, recGest;
			msgString = msg[1].asString;
			msgArgs =  Dictionary.newFrom(JSON.parse(msgString)); // n.b. JSON.parse returns an Event

			// this parsing is only to catch errors, whole dictionary is returned instead
			// id = msgArgs.at(\nodeID).asInteger; // origin ID, not used in CombMode. CombTargets used instead.
			x = msgArgs.at(\x).asFloat; // phi in map
			y = msgArgs.at(\y).asInteger; // l in map
			type = msgArgs.at(\type).asInteger; // discrete = 0, continuous = 1, ring = 2
			idx = msgArgs.at(\idx).asInteger; // pointer index (>10 in ring type)
			pressed = msgArgs.at(\pressed).asInteger; // pressed/released in continuous voice type
			combTargets = msgArgs.at(\combTargets).asArray;
			recGest = msgArgs.at(\recGest).asInteger; // gesture
			combAlg = msgArgs.at(\combAlg);

		}.try({ Error("TopBridge.parsetopCombMessages(): can't parse /topComb message: " +msg).throw });
		^msgArgs;
	}

	parsetopSendToWFSMessages{|msg|
		/* try to parse /topSendToWFS
		@params: OSCmessage msg (Array[String path, String msgArray])
		@return: parsedMsg[int id]
		*/
		var msgString, msgArgs, parsedMsg;

		// Error checking
		{ var id;
			msgString = msg[1].asString;
			msgArgs = msgString.split($,);

			msgArgs[0]; // "PolyTop", discarded header of message,
			id = msgArgs[1].asInteger; // origin ID
			parsedMsg = [id];
		}.try({ Error("TopBridge.parsetopSendToWFSMessages(): can't parse /topSendToWFS message: " +msg).throw });
		^parsedMsg;
	}

	parsetopAddMessages{|msg|
		/* try to parse /topAdd message and report any errors
		@params: OSCmessage msg (Array[String path, String msgJSON])
		*/
		var msgString, msgArgs, parsedMsg;
		// Error checking
		{ var phi, l, x, y, idOrigin, idChild, inlinks, outlinks, linkName;
			msgString = msg[1].asString;
			msgArgs = Dictionary.newFrom(JSON.parse(msgString)); // n.b. JSON.parse returns an Event

			// this parsing is only to catch errors, whole dictionary is returned instead
			idChild = msgArgs.at(\originID); // child ID
			linkName = msgArgs.at(\linkName); // name of the incoming link
			idOrigin = msgArgs.at(\parentID); // origin ID
			phi = msgArgs.at(\phi).asFloat; // phi in map
			l = msgArgs.at(\l); // l in map
			x = msgArgs.at(\x); // absolute x
			y = msgArgs.at(\y); // absolute y
			inlinks = msgArgs.at(\inlinkIDs);
			outlinks = msgArgs.at(\outlinkIDs);

		}.try({ Error("TopBridge.parsetopAddMessages(): can't parse /topAdd message: " +msg).throw });
		^msgArgs;
	}

	parsetopDeleteMessages{|msg|
		/* try to parse /topDelete
		@params: OSCmessage msg (Array[String path, String msgArray])
		@return: parsedMsg[int id]
		*/
		var msgString, msgArgs, parsedMsg;

		("TopBridge:parsetopDeleteMessages(): raw msg" + msg).postln;

		// Error checking
		{ var id;
			msgString = msg[1].asString;
			msgArgs = msgString.split($,);

			msgArgs[0]; // "PolyTop", discarded header of message,
			id = msgArgs[1].asInteger; // origin ID
			parsedMsg = [id]
		}.try({ Error("TopBridge.parsePolyDeleteMessages(): can't parse /topDelete message: " +msg).throw });
		^parsedMsg;
	}

	reloadGraph {|path = \default|
		/* convenience method that loads a TopNodeGraph and reinitializes the bridge with same parameters */
		this.closeBridge();

		if (path == \default, {
			this.nodeGraph = TopNodeGraph.loadGraph();
		}, {
			this.nodeGraph = TopNodeGraph.loadGraph(path);
		});
		^TopBridge.new(this.clientReceiverAddr.ip, this.nodeGraph, this.topAlgs);
	}

	closeBridge {
		/* Closes all client listeners.
		*/
		listopPlay.free;
		listopComb.free;
		listopSendToWFS.free;
		listopSyncReply.free;
		listopAdd.free;
		listopDelete.free;
		lisTopSetGenAlg.free;
		lisTopSetCombAlg.free;
	}


	// TopBridge helper methods

	isValidIP {|ip|
		/* checks if IP is valid
		@params: String ip;
		@return: Boolean validity

		Uses regexp from "http://stackoverflow.com/questions/10006459/regular-expression-for-ip-address-validation"
		TODO: false positive at ip = "1234567", needs a better regexp
		*/
		var pass = nil, regExpCond = "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";
		// ("TopBridge.isValidIP(): ip - " + ip).postln; // DEBUG
		pass = regExpCond.matchRegexp(ip);
		^pass;
	}
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

PolyContents {
	/* Superclass for contents of PolyTop nodes - i.e. PolyState, PolyEventGen, PolySO, PolyLiveSurf

	Standards of naming in SuperCollider Synth based objects:
	- s_var, static variable. Automatically excluded by ParseArgsPolyTop.class.
	- b_var, buffer name. Automatically excluded by ParseArgsPolyTop.class.
	- t_var, trigger variable. Automatically excluded by ParseArgsPolyTop.class.
	variables can futher be excluded from manipulation in TopNodeMap via GenAlg/CombAlg. This allows explicit declaration during init of PolyState, but prevents manipulation.
	*/

	printContents {
		/* prints contents of the PolyContents in a nice array */
		("DefName: " + this.defName).postln;
		("paramNames: " + this.paramNames).postln;
		("nVoices: " + this.pppv.cols).postln;

		("pppv: ").postln;
		this.pppv.rowsDo({ |row, idx| (">>> " + this.paramNames[idx] + ": " + row.round(0.00001)).postln });
	}

	getDiff {|other|
		/* DEPRECATED */
		/* gets sum difference between this and other contents */
		^(this.pppv.contents.asArray - other.pppv.contents.asArray).abs.sum;
	}

	setParam{|name, value|
		/* sets all voices of the param to the same value, mutable
		@params: string/symbol name, number/function value
		*/
		var idx, nVoices;
		nVoices = this.pppv.cols;
		idx = this.paramNames.find([name.asSymbol]);

		if (idx == nil,
			{ "Parameter with name " + name + " not found!".postln; },
			{
				nVoices.do({|voiceIdx| this.pppv.put(idx, voiceIdx, value.value)}); // value can be function, thus {}.value
		});
		// this.printContents; // DEBUG
	}

	getParam{|name|
		/* gets all voices of the param, immutable
		@params: string/symbol name
		*/
		var idx, nVoices, return;
		nVoices = this.pppv.cols;
		idx = this.paramNames.find([name.asSymbol]);

		if (idx == nil,
			{ "Parameter with name " + name + " not found!".postln; },
			{
				^this.pppv.rowAt(idx);
		});
	}

	editParam{|name, func|
		/* apply a function to all voices of a parameter, mutable
		@params: string/symbol name, function func
		*/
		var idx, nVoices;
		nVoices = this.pppv.cols;
		idx = this.paramNames.find([name.asSymbol]);

		if (func == nil, { func == {|oldVoiceValues| oldVoiceValues}}); // default function does not do anything

		if (idx == nil,
			{ "Parameter with name " + name + " not found!".postln; },
			{
				var newVoiceValues;
				newVoiceValues = func.value(this.getParam(name));
				nVoices.do({|voiceIdx| this.pppv.put(idx, voiceIdx, newVoiceValues[voiceIdx])});
		});
		// this.printContents; // DEBUG
	}
}

PolyLiveSurf : PolyContents {
	/* defines a playable state of sound object, with synthesis in Ableton Live.
	Allows you to control Ableton Live Devices with PolyTop map that maps parameters <automatically>:
	- PolyTop acts as a Control Surface(s) in Live.
	- User must place PolyTop surface files in /Users/<UserName>/Library/Preferences/Ableton/Live <x.x.x>/User Remote Scripts/
	- 6 surfaces allowed, each with up to 8 controls. User must lock each surface manually to different device/track. Every device in Live decides which 8 params are most important/possible.
	- Each voice in pppv is a separate control surface in Live.
	*/
	var
	<>pppv = nil, // Array2D pppv, rows of voices (!), items are floats. MUTABLE!
	<>pppv_probs = nil, // Array2D, same structure as pppv, holds probability p that genAlg will modulate value up. Edited at every step.
	<>midiOut = nil, // MIDIOut endpoint to send MIDI to
	<>defName = "Ableton Live", // for backwards compatability with some methods (i.e. lisTopComb)
	<>idxSurf = 0, // when a PolyLiveSurf node controls <6 surfaces, idxSurf specifies which surfaces are controlled
	<>nSurf = 0;  // number of surfaces to control with this node. Only used for error checking, must be equal to pppv.cols

	*new {|pppv, midiOut, nSurf, idxSurf, pppv_probs|
		/*
		@params:
		Array2D pppv (pppv)
		MIDIOut midiOut
		int nSurf - number of surfaces to control with this node
		int idxSurf - which surface to control with this node (or starting surface if nSurf > 1)
		*/
		^super.new.init(pppv, midiOut, nSurf, idxSurf, pppv_probs);
	}

	*newDefault {|midiOut, defVal = 64, nSurf = 6, idxSurf = 0|
		/* creates all 6 surfaces compatible with Live 9, with params set at midi-64 */
		var nParams = 8, array2d, pppv;
		array2d = nParams.collect({ defVal.dup(nSurf)}).flat; // redundant, just if different default values need to be initiated
		pppv = Array2D.fromArray(nParams, nSurf, array2d);

		^super.new.init(pppv, midiOut, nSurf, idxSurf);
	}

	init {|pppv, midiOut, nSurf, idxSurf, pppv_probs|
		// Error checking
		if (pppv.class != Array2D, { Error("PolyLiveSurf.init(): pppv are not of type Array2D").throw });
		if (midiOut == nil, {Error("PolyLiveSurf.init(): midiOut is nil").throw });
		if (pppv.cols != nSurf, { Error("PolyLiveSurf.init(): nSurf has to be equal to number of columns in pppv (each column is a seperate voice).]").throw; });

		this.pppv = pppv;
		this.pppv_probs = pppv_probs;
		this.midiOut = midiOut;
		this.idxSurf = idxSurf;
		this.nSurf = nSurf;
	}

	duplicate {
		var newState;
		newState = PolyLiveSurf.new(this.pppv, this.midiOut, this.nSurf, this.idxSurf, this.pppv_probs);
		^newState;
	}

	playDiscrete {
		Error("PolyLiveSurf.playDiscrete(): playDiscrete() not supported. Reason: PolyLiveSurf controls Ableton Live continuously").throw;
	}

	playContinuous {|currentSynths = nil|
		/* Plays / Updates a Ableton Live surfaces
		- parameters are left in only for compatability, no synths are created
		@params: Synths[] currentSynths
		@return: Synths[] currentSynths
		*/
		// todo implement MIDI sending
		var ccIdx = 10 + (8 * idxSurf); // starting at CC-10
		pppv.cols.do({|cidx| // for each surface...
			pppv.rows.do({|ridx| // for each param...
				// ("PlayLiveSurf.playContinuous: ccIdx: " + ccIdx + "; pppv val: " + pppv.at(ridx, cidx).asInteger).postln; // DEBUG
				this.midiOut.control(0, ccIdx, pppv.at(ridx, cidx).asInteger);
				ccIdx = ccIdx + 1;
		})});
		^currentSynths;
	}

	playBehavior {
		Error("PolyLiveSurface.playBehavior(): playBehavior() not supported. Reason: not implemented").throw;
	}

	editParam {
		Error("PolyLiveSurface.editParam(): editParam() not supported.").throw;
	}

	setParam {
		Error("PolyLiveSurface.setParam(): editParam() not supported.").throw;
	}

	getParam {
		Error("PolyLiveSurface.getParam(): editParam() not supported.").throw;
	}

}

PolyLiveMap : PolyContents {
	/* defines a playable state of sound object, with synthesis in Ableton Live.
	Allows mapping params to specific Ableton Live Device parameters:
	- nVoices is always 1. nParams <= 128
	- User must map each parameter in pppv to a parameter in Ableton Live using PolyLiveMap.map(ccIdx), PolyLiveMap.mapNext()
	*/

	var
	<>pppv = nil, // Array2D pppv
	<>midiOut = nil, // MIDIOut endpoint to send MIDI to
	<>defName = "Ableton Live", // for backwards compatability with some methods (i.e. lisTopComb)
	<>ccMap = nil, // Dictionary of param to cc mapping
	<>paramTarget = 0, // .mapNext() target
	<>paramOffset = 0, // when multiple PolyLiveMaps are used, paramOffset prevents range conflict when using mapNext()
	<>mapperRoutine = nil, // Routine that is refreshing a single MIDI value to be mapped in Ableton Live
	<>trigNote = 0;

	*new {|pppv, midiOut, paramOffset|
		/*
		@params:
		Array2D pppv (pppv)
		MIDIOut midiOut
		int paramOffset - offset to prevent range conflict when using multiple PolyLiveMaps and mapNext
		*/
		^super.new.init(pppv, midiOut, paramOffset);
	}

	*newDefault {|midiOut, defVal = 64, nParams = 128, paramOffset = 0|
		/* initialized default 1D array of 128 values */
		var array2d, pppv;
		array2d = {defVal}.dup(nParams); // redundant, just if different default values need to be initiated
		pppv = Array2D.fromArray(nParams, 1, array2d);

		^super.new.init(pppv, midiOut, paramOffset);
	}

	init {|pppv, midiOut, paramOffset|
		// Error checking
		if (pppv.class != Array2D, { Error("PolyLiveMap.init(): pppv are not of type Array2D").throw });
		if (midiOut == nil, {Error("PolyLiveMap.init(): midiOut is nil").throw });
		if (pppv.cols != 1, { Error("PolyLiveMap.init(): number of columns in pppv has to be equal to 1 (no voice separation in PolyLiveMap).]").throw; });

		this.pppv = pppv;
		this.midiOut = midiOut;
		this.paramOffset = paramOffset;
		this.paramTarget = this.paramOffset;
		this.ccMap = Dictionary.newFrom(128.collect({|idx| [idx, idx+paramOffset]}).flat);
	}

	duplicate {
		var newState; // TODO: rename to newContents for more unified naming in all similar classes
		newState = PolyLiveMap.new(this.pppv, this.midiOut, this.paramOffset);
		^newState;
	}

	playDiscrete {
		Error("PolyLiveMap.playDiscrete(): playDiscrete() not supported. Reason: PolyLiveMap controls Ableton Live continuously").throw;
	}

	/*map {|paramIdx, ccIdx|
	// todo: test and finish
	/* maps param index to CC */
	if (paramIdx > this.pppv.rows, {Error("PolyLiveMap.map(): param idx is out of bounds. [paramIdx, nParams]" + [paramIdx, pppv.rows]).throw });
	if (ccIdx < this.paramOffset, {Error("PolyLiveMap.map(): CC idx is out of bounds (ccIdx < paramOffset) [ccIdx, paramOffset]" + [ccIdx, this.paramOffset]).throw });
	this.ccMap.put(paramIdx, ccIdx);

	if (this.mapperRoutine != nil, {this.mapperRoutine.stop});
	this.mapperRoutine = Routine.new({
	{
	this.midiOut.control(0, this.ccMap.at(paramIdx), 0);
	0.25.wait;
	}.loop;
	});
	this.mapperRoutine.play;
	}*/


	mapNext {
		var ccTarget = this.paramTarget + this.paramOffset;
		this.ccMap.put(this.paramTarget, ccTarget);

		this.ccMap.put(paramTarget, ccTarget);

		if (this.mapperRoutine != nil, {this.mapperRoutine.stop});
		this.mapperRoutine = Routine.new({
			{
				this.midiOut.control(0, this.ccMap.at(this.paramTarget), 0);
				0.25.wait;
			}.loop;
		});
		this.mapperRoutine.play;

		this.paramTarget = this.paramTarget + 1;
		if (this.paramTarget > this.pppv.rows, { this.paramTarget = 0 });
	}

	endMap {
		this.mapperRoutine.stop;
	}

	setTrigNote {|noteMidi|
		/* sets MIDI note to be played with gesture */
		this.trigNote = noteMidi;
	}

	playContinuous {|currentSynths = nil|
		/* Plays / Updates a Ableton Live surfaces
		- parameters are left in only for compatability, no synths are created
		@params: Synths[] currentSynths
		@return: Synths[] currentSynths
		*/
		// todo implement MIDI sending
		pppv.rows.do({|ridx| // for each param...
			// ("PlayLiveSurf.playContinuous: ccIdx: " + ccIdx + "; pppv val: " + pppv.at(ridx, cidx).asInteger).postln; // DEBUG
			this.midiOut.control(0, this.ccMap.at(ridx), pppv.at(ridx, 0).asInteger);
		});
		^currentSynths;
	}

	playBehavior {
		Error("PolyLiveMap.playBehavior(): playBehavior() not supported. Reason: not implemented").throw;
	}

	editParam {
		Error("PolyLiveMap.editParam(): editParam() not supported.").throw;
	}

	setParam {
		Error("PolyLiveMap.setParam(): editParam() not supported.").throw;
	}

	getParam {
		Error("PolyLiveMap.getParam(): editParam() not supported.").throw;
	}

}

PolyState : PolyContents {
	/* defines a playable state of sound object (defname, paramNames, params * voices values)
	*/
	var
	<>defName = nil, // Symbol defName
	<>paramNames = nil, // Symbol[] paramNames
	<>pppv = nil, // Array2D pppv, rows of voices (!), items are floats. MUTABLE!
	<>synths = nil;

	*new {|defName, paramNames, pppv|
		/*
		@params: String defName, Symbol[] paramNames, Array2D pppv (pppv)
		*/
		^super.new.init(defName, paramNames, pppv);
	}

	*newDefault {|defName, nVoices|
		/*
		default value initializer
		@params: String defName, int nVoices
		param names and values for contents are taken from SynthDef defaults
		*/
		^super.new.default(defName, nVoices);
	}

	init {|defName, paramNames, pppv|
		/* init
		*/
		// Error checking
		if (defName.class != Symbol, { Error("PolyState.init(): defName not type Symbol").throw });
		if (paramNames.class != Array, { Error("PolyState.init(): paramNames is not Array").throw });
		if (pppv.class != Array2D, { Error("PolyState.init(): pppv are not of type Array2D").throw });
		// TODO check if SynthDef is available
		if (paramNames[0].class != Symbol, { Error("PolyState.init(): paramNames[0] not a Symbol").throw });
		if (paramNames.size != pppv.rows, { Error("PolyState.init(): mismatch between paramNames.size and column number in pppv").throw }
		);

		this.defName = defName;
		this.paramNames = paramNames;
		this.pppv = pppv;
		~maxPeakCPU = 80;

	}

	default {|defName, nVoices|
		/* init from SynthDef default vals. Uses ParseArgsPolyTop
		*/
		var def, names, vals, array2d, pppv;
		def = ParseArgsPolyTop.new(defName);
		names = def.makeCCnames.asArray;
		vals = def.makeCCvals.asArray;

		// make simple Array2D --> pppv
		array2d = vals.collect({|it| it.dup(nVoices)}).flat;
		pppv = Array2D.fromArray(names.size, nVoices, array2d);

		this.init(defName, names, pppv);
	}

	duplicate {
		var newState;
		newState = PolyState.new(this.defName, this.paramNames, this.pppv);
		^newState;
	}

	playDiscrete {
		/* Plays a state as discrete sound
		*/
		var setSynthArgs;
		setSynthArgs = this.generateSetSynthArgs();
		this.synths = setSynthArgs.collect { |args, idx| // init Synth for each voice
			("playDiscrete(): args for the synth " + args).postln;
			// ("PolyState.playDiscrete(): Args of voice " + idx + ": " + args).postln; // DEBUG
			if (Server.default.peakCPU < ~maxPeakCPU, { // prevent Server crash by checking server usage
				Synth(defName, args, Server.default).set(\t_released, 1, \glob_gain, ~glob_gain, \c_tresh, ~cTresh, \c_slope_below, ~cSlopeBelow, \c_slopeAbove, ~cSlopeAbove, \c_clamp, ~cClampTime, \c_relax, ~cRelaxTime, \s_voice_amp, ~strandVolumes[idx], \s_idx, idx, \s_poly_count, setSynthArgs.size);
			}, // if peak CPU > 80%
			{
				Error("PolyContents.playDiscrete(): CPU overload near 80%, discarding message").throw;
			});
		}
	}

	playContinuous {|currentSynths = nil|
		/* Plays / Updates a state as continuous sound
		@params: Synths[] currentSynths, used by the TopBridge to track specific synths
		@return: Synths[] currentSynths
		*/

		if ( currentSynths == nil,
			{
				currentSynths = this.generateInitSynthArgs().collect { |args, idx| // parse arg sets for each voice
					// ("PolyState.playContinuous(): Args of new voice " + idx + ": " + args).postln; // DEBUG
					if (Server.default.peakCPU < ~maxPeakCPU, { // prevent Server crash by checking server usage
						Synth(defName, args, Server.default);
					}, // if peak CPU > 80%
					{
						Error("PolyContents.playContinuous(): discarding message").throw;
					});
				};
			},
			{
				var setSynthArgs;
				setSynthArgs = this.generateSetSynthArgs();
				setSynthArgs.do({|argPairs, idxVoice|
					// ("PolyState.playContinuous(): Args of old voice " + idxVoice + ": " + argPairs).postln; // DEBUG
					// Synths can't be set with an array of arguments (only instantiated...), so set by pairs
					argPairs.do({|argPair|
						// ("PolyState.playContinuous(): arg pair of old voice " + idxVoice + ": " + argPair).postln; // DEBUG
						currentSynths[idxVoice].set(argPair[0], argPair[1]);
					});
					currentSynths[idxVoice].do({ |synth| synth.set(\glob_gain, ~glob_gain,  \c_tresh, ~cTresh, \c_slope_below, ~cSlopeBelow, \c_slopeAbove, ~cSlopeAbove, \c_clamp, ~cClampTime, \c_relax, ~cRelaxTime, \s_voice_amp, ~strandVolumes[idxVoice], \s_idx, idxVoice, \s_poly_count, setSynthArgs.size)});
				});
			}
		);
		^currentSynths;
	}

	playBehavior {
		Error("PolyState.playBehavior(): PolyState does not support playBehavior(). Use PolySO instead").throw;
	}


	// Helper methods
	generateInitSynthArgs {
		/* creates pvpp ([param, name] pairs) for setting synths
		@return: String[], format [v1[\arg1, *, arg2, *], v2[\arg1, *, \arg2, *..]..]
		*/
		var args, nVoices;
		nVoices = pppv.cols;
		args = nVoices.collect({|idx| var voiceArgs, colOfParams;
			colOfParams = pppv.colAt(idx);
			voiceArgs = paramNames.collect({|it, idx|
				[it, colOfParams[idx]];
			});
			// ("TopState.generateSynthArgs(): voice " + idx + ", args " + voiceArgs).postln; // DEBUG
			voiceArgs.flat; // see .new() vs .set() difference for why!...
		});
		^args;
	}

	generateSetSynthArgs {
		/* creates pvpp ([param, name] pairs) for setting synths
		@return: String[], format [v1[[\arg1, *], [arg2, v*]], v2[[\arg1, *], [\arg2, *]..]..]
		*/
		var args, nVoices;
		nVoices = pppv.cols;
		args = nVoices.collect({|idx| var voiceArgs, colOfParams;
			colOfParams = pppv.colAt(idx);
			voiceArgs = paramNames.collect({|it, idx|
				[it, colOfParams[idx]];
			});
			// ("TopState.generateSynthArgs(): voice " + idx + ", args " + voiceArgs).postln; // DEBUG
			voiceArgs;
		});
		^args;
	}

	/*stop {
	/* stops the contents from playing by killing its synths */
	if (this.synths != nil, {
	synths.do({|synth|
	synth.free();
	});
	});
	}*/

	asCompileString {
		/* representation of PolyState for saving as JSON */
		var interString, nP, nV, pppvString;
		nP = this.paramNames.size();
		nV = this.pppv.cols;

		// n.b. since Array2D does not have an interpretable asCompileString result, we have to make it here...
		pppvString = "Array2D.fromArray(" + nP + ", " + nV + ", " + this.pppv.asArray.asCompileString + ")";
		interString = "PolyState.new(" + this.defName.asCompileString + ", " + this.paramNames.asCompileString + ", " + pppvString + ")";
		^interString
	}
}

/* todo
- suspended because unclear how to inherit from another object and initiate correctly in SC

PolySO : PolyState {
/* PolyState + Behavior */
var
<>behavior = nil;

*new {|defName, paramNames, pppv, behavior|
/*
@params: String defName, Symbol[] paramNames, Array2D pppv (pppv)
*/
^super.new.init(defName, paramNames, pppv, behavior);
}

init {|defName, paramNames, pppv, behavior|
/* init
*/
// Call parent to initialize

("defName, paramNames, pppv, behavior:" + [defName, paramNames, pppv, behavior]).post;

super.init(defName, paramNames, pppv);

// Error checking
if (defName.class != Symbol, { Error("PolyState.init(): defName not type Symbol").throw });
if (paramNames.class != Array, { Error("PolyState.init(): paramNames is not Array").throw });
if (pppv.class != Array2D, { Error("PolyState.init(): pppv are not of type Array2D").throw });
// TODO check if SynthDef is available
if (paramNames[0].class != Symbol, { Error("PolyState.init(): paramNames[0] not a Symbol").throw });
if (paramNames.size != pppv.rows, { Error("PolyState.init(): mismatch between paramNames.size and column number in pppv").throw }
);

this.defName = defName;
this.paramNames = paramNames;
this.pppv = pppv;
this.behavior = behavior;
~maxPeakCPU = 80;
}
}
*/

PolySO : PolyContents {
	/* defines a playable state of sound object (defname, paramNames, params * voices values) with short segment of behavior
	- todo: should inherit from PolyState, now a lot of duplicate code
	*/
	var
	<>defName = nil, // Symbol defName
	<>paramNames = nil, // Symbol[] paramNames
	<>pppv = nil, // Array2D pppv, rows of voices (!), items are floats. MUTABLE!
	<>synths = nil,
	<>behavior = nil; // array(Behavior (env)...), that contains Behavior object (env data + routine).

	*new {|defName, paramNames, pppv, behavior|
		/*
		@params: String defName, Symbol[] paramNames, Array2D pppv (pppv)
		*/
		^super.new.init(defName, paramNames, pppv, behavior);
	}

	*newDefault {|defName, nVoices|
		/*
		default value initializer
		@params: String defName, int nVoices
		param names and values for contents are taken from SynthDef defaults
		*/
		^super.new.default(defName, nVoices);
	}

	init {|defName, paramNames, pppv, behavior|
		/* init
		*/
		// Error checking
		if (defName.class != Symbol, { Error("PolyState.init(): defName not type Symbol").throw });
		if (paramNames.class != Array, { Error("PolyState.init(): paramNames is not Array").throw });
		if (pppv.class != Array2D, { Error("PolyState.init(): pppv are not of type Array2D").throw });
		// TODO check if SynthDef is available
		if (paramNames[0].class != Symbol, { Error("PolyState.init(): paramNames[0] not a Symbol").throw });
		if (paramNames.size != pppv.rows, { Error("PolyState.init(): mismatch between paramNames.size and column number in pppv").throw }
		);

		this.defName = defName;
		this.paramNames = paramNames;
		this.pppv = pppv;
		this.behavior = behavior;
		~maxPeakCPU = 80;
	}

	default {|defName, nVoices|
		/* init from SynthDef default vals. Uses ParseArgsPolyTop
		*/
		var def, names, vals, array2d, pppv, behavior;
		def = ParseArgsPolyTop.new(defName);
		names = def.makeCCnames.asArray;
		vals = def.makeCCvals.asArray;

		// make simple Array2D --> pppv
		array2d = vals.collect({|it| it.dup(nVoices)}).flat;
		pppv = Array2D.fromArray(names.size, nVoices, array2d);

		behavior = Array.fill(nVoices, { StrandBehavior.new(10.collect({|idx| if (idx%2 == 0, { [idx/10.0, 1.0.rand, rrand(-4, 4)] }, { [idx/10.0, 0, rrand(-4, 4)] }) }) )}); // todo change with more sequencer-like behavior
		this.init(defName, names, pppv, behavior);

	}

	duplicate {
		var newSO;
		newSO = PolySO.new(this.defName, this.paramNames, this.pppv, this.behavior);
		^newSO;
	}

	playDiscrete {
		/* Plays a state as discrete sound
		*/
		var setSynthArgs;
		setSynthArgs = this.generateSetSynthArgs();
		this.synths = setSynthArgs.collect { |args, idx| // init Synth for each voice
			// ("PolyState.playDiscrete(): Args of voice " + idx + ": " + args).postln; // DEBUG
			("playDiscrete(): args for the synth " + args).postln;

			if (Server.default.peakCPU < ~maxPeakCPU, { // prevent Server crash by checking server usage
				Synth(defName, args, Server.default).set(\t_released, 1, \glob_gain, ~glob_gain, \c_tresh, ~cTresh, \c_slope_below, ~cSlopeBelow, \c_slopeAbove, ~cSlopeAbove, \c_clamp, ~cClampTime, \c_relax, ~cRelaxTime, \s_voice_amp, ~strandVolumes[idx], \s_idx, idx, \s_poly_count, setSynthArgs.size);
			}, // if peak CPU > 80%
			{
				Error("PolyContents.playDiscrete(): CPU overload near 80%, discarding message").throw;
			});
		}
	}

	playContinuous {|currentSynths = nil|
		/* Plays / Updates a state as continuous sound
		@params: Synths[] currentSynths, used by the TopBridge to track specific synths
		@return: Synths[] currentSynths
		*/

		if ( currentSynths == nil,
			{
				currentSynths = this.generateInitSynthArgs().collect { |args, idx| // parse arg sets for each voice
					// ("PolyState.playContinuous(): Args of new voice " + idx + ": " + args).postln; // DEBUG
					if (Server.default.peakCPU < ~maxPeakCPU, { // prevent Server crash by checking server usage
						Synth(defName, args, Server.default);
					}, // if peak CPU > 80%
					{
						Error("PolyContents.playContinuous(): discarding message").throw;
					});
				};
			},
			{
				var setSynthArgs;
				setSynthArgs = this.generateSetSynthArgs();
				this.synths = setSynthArgs.do({|argPairs, idxVoice|
					// ("PolyState.playContinuous(): Args of old voice " + idxVoice + ": " + argPairs).postln; // DEBUG
					// Synths can't be set with an array of arguments (only instantiated...), so set by pairs
					argPairs.do({|argPair|
						// ("PolyState.playContinuous(): arg pair of old voice " + idxVoice + ": " + argPair).postln; // DEBUG
						currentSynths[idxVoice].set(argPair[0], argPair[1]);
					});
					currentSynths[idxVoice].do({ |synth| synth.set(\glob_gain, ~glob_gain,  \c_tresh, ~cTresh, \c_slope_below, ~cSlopeBelow, \c_slopeAbove, ~cSlopeAbove, \c_clamp, ~cClampTime, \c_relax, ~cRelaxTime, \s_voice_amp, ~strandVolumes[idxVoice], \s_idx, idxVoice, \s_poly_count, setSynthArgs.size)});
				});
			}
		);
		^currentSynths;
	}

	playBehavior {|currentSynths = nil, voiceBehavior|
		/* Plays / Updates a SO as continuous sound for the duration of bahavior
		@params:
		- Synths[] currentSynths, used by the TopBridge to track specific synths
		- Behavior[] voiceBehavior, used by the TopBridge to track playing behaviors
		@return: Synths[] currentSynths
		*/
		var synthArgs;
		synthArgs = this.generateSetSynthArgs();

		if ( currentSynths == nil,
			{
				currentSynths = synthArgs.collect { |args, idx| // parse arg sets for each voice
					// ("PolyState.playBehavior(): Args of new voice " + idx + ": " + args).postln; // DEBUG
					if (Server.default.peakCPU < ~maxPeakCPU, { // prevent Server crash by checking server usage
						Synth(defName, args, Server.default);
					}, // if peak CPU > 80%
					{
						Error("PolyContents.playBehavior(): discarding message").throw;
					});
				};
			},
			{
				synthArgs.do({|argPairs, idxVoice|
					// ("PolyState.playBehavior(): Args of old voice " + idxVoice + ": " + argPairs).postln; // DEBUG
					// Synths can't be set with an array of arguments (only instantiated...), so set by pairs
					argPairs.do({|argPair|
						// ("PolyState.playBehavior(): arg pair of old voice " + idxVoice + ": " + argPair).postln; // DEBUG
						currentSynths[idxVoice].set(argPair[0], argPair[1]);
					});
					currentSynths[idxVoice].do({ |synth| synth.set(\glob_gain, ~glob_gain,  \c_tresh, ~cTresh, \c_slope_below, ~cSlopeBelow, \c_slopeAbove, ~cSlopeAbove, \c_clamp, ~cClampTime, \c_relax, ~cRelaxTime, \s_voice_amp, ~strandVolumes[idxVoice], \s_idx, idxVoice, \s_poly_count, synthArgs.size)});
				});
			}
		);

		// play/update voiceBehavior
		if (voiceBehavior == nil, { voiceBehavior = Array.fill(synthArgs.size, {|idx| this.behavior[idx]}) });

		voiceBehavior = voiceBehavior.collect({|strandBehavior, strandIdx|
			if (strandBehavior == nil, { Error("playBehavior(): strandBehavior is nil, while voice behavior is not nil. Something went wrong, aborting...").throw; });
			if (strandBehavior.isPlaying,
				{
					/* do nothing */
					("playBehavior(): strand " + strandIdx + " is playing. Doing nothing").postln;
				},
				{
					/*if (strandBehavior == this.behavior[strandIdx], // same cell is retriggered
					{
					("StrandBehavior 2: " + strandBehavior).postln;
					strandBehavior.reset.play(currentSynths[strandIdx]);
					},

					{ // else, new cell behavior is played*/
					strandBehavior = behavior[strandIdx];
					strandBehavior.play(currentSynths[strandIdx]);
					// });
				}
			);
			strandBehavior;
		});

		^[currentSynths, voiceBehavior];
	}


	// Helper methods
	generateInitSynthArgs {
		/* creates pvpp ([param, name] pairs) for setting synths
		@return: String[], format [v1[\arg1, *, arg2, *], v2[\arg1, *, \arg2, *..]..]
		*/
		var args, nVoices;
		nVoices = pppv.cols;
		args = nVoices.collect({|idx| var voiceArgs, colOfParams;
			colOfParams = pppv.colAt(idx);
			voiceArgs = paramNames.collect({|it, idx|
				[it, colOfParams[idx]];
			});
			// ("TopState.generateSynthArgs(): voice " + idx + ", args " + voiceArgs).postln; // DEBUG
			voiceArgs.flat; // see .new() vs .set() difference for why!...
		});
		^args;
	}

	generateSetSynthArgs {
		/* creates pvpp ([param, name] pairs) for setting synths
		@return: String[], format [v1[[\arg1, *], [arg2, v*]], v2[[\arg1, *], [\arg2, *]..]..]
		*/
		var args, nVoices;
		nVoices = pppv.cols;
		args = nVoices.collect({|idx| var voiceArgs, colOfParams;
			colOfParams = pppv.colAt(idx);
			voiceArgs = paramNames.collect({|it, idx|
				[it, colOfParams[idx]];
			});
			// ("TopState.generateSynthArgs(): voice " + idx + ", args " + voiceArgs).postln; // DEBUG
			voiceArgs;
		});
		^args;
	}

	/*stop {
	/* stops the contents from playing by killing its synths */
	if (this.synths != nil, {
	synths.do({|synth|
	synth.free();
	});
	});
	}*/

	asCompileString {
		/* representation of PolyState for saving as JSON */
		var interString, nP, nV, pppvString;
		nP = this.paramNames.size();
		nV = this.pppv.cols;

		// n.b. since Array2D does not have an interpretable asCompileString result, we have to make it here...
		pppvString = "Array2D.fromArray(" + nP + ", " + nV + ", " + this.pppv.asArray.asCompileString + ")";
		interString = "PolySO.new(" + this.defName.asCompileString + ", " + this.paramNames.asCompileString + ", " + pppvString + ")";
		^interString
	}
}

StrandBehavior {
	/* plays behavior once (of its synth) and waits to be reset
	*/
	var
	<>envXYC = nil, // xyx envelope points
	<>routine = nil,
	<>synth = nil;

	*new {|envXYC|
		^super.new.init(envXYC)
	}

	init{|envXYC|
		// error checking (todo)
		this.envXYC = envXYC;
		this.routine =
		Routine({
			var nSteps = 1024, envelope;
			envelope = Env.xyc(this.envXYC);
			envelope.discretize(nSteps).do({|val|
				if (this.synth == nil, { Error("StrandBehavior: synth is nil, something went wrong!").throw });
				this.synth.set(\s_amp, val);
				(envelope.duration / nSteps).wait;
			});
		});
	}

	duplicate {
		^StrandBehavior.new(this.envXYC.deepCopy);
	}

	play {|synth|
		this.synth = synth;
		this.routine.play;
		^this.routine;
	}

	stop {
		this.routine.stop;
	}

	reset {
		this.routine.reset;
		^this.routine;
	}

	isPlaying {
		^this.routine.isPlaying;
	}


}

PolyEventGen : PolyContents {
	/*
	- holds a sequence of PolyStates, that represent a contiuous transformation of a sound object. Implemented as an Array of PolyStates.
	- moves through node tree.
	*/
	var
	<>seq,
	<>routine,
	<>period,
	<>synths,
	<>cont; // boolean - continue after finishing?

	*new {|nodeGraph, mapA, mapB|
		/* empty new
		*/
		^super.new.init(nodeGraph, mapA, mapB);
	}

	init {|nodeGraph, mapA, mapB|
		/* Creates a max resolution sequence of states (nodes) between two nodes in a graph
		*/

		this.seq = nodeGraph.getStateSequence(mapA, mapB);
		this.routine = Routine {

			if (this.synths != nil, { this.stop(); }); // prevents Failure in Server message
			this.synths = nil;

			this.seq.do({|node|
				this.synths = node.contents.playContinuous(this.synths);
				this.period.wait;
			});
			// finish playing at the end of sequence
			if (this.cont == false, {
				if (this.synths != nil, {
					this.synths.do({|synth|
						synth.set(\t_released, 1);
					});
				});
			});

			this.yieldAndReset;
		}
	}

	play {|dur, cont = false|
		/* plays back the sequence of total dur (s)
		*/
		this.cont = cont;
		this.period = dur / this.seq.size;
		this.routine.play;
	}

	stop {
		this.synths.do({|synth|
			synth.set(\t_released, 1);
	});	}
}



/* todo

PolyEventComb : PolyContents
/*
- holds a sequence of PolyStates, that represent a contiuous transformation of a sound object. Implemented as an Array of PolyStates.
- moves through comb plane.
*/
*/

// WFS collider data structures, todo

/*
UPolyState : UEvent {
// TODO: this does not work yet. Write a class that extends UEvent and shows up as UScoreView inside UScoreEditor. Requirements:
- shows up in gui.
- has startTime, endTime.
- opt. has fades
- opt. double clicking opens up PolyPhone editor.
- opt. ability to send new identity back to PolyTop (i.e. a copy, an abstraction).
~topBridge.closeBridge(); // clears all OSCFuncs

/*Holds PolyState and implements UEvent interface:
- startTime - set externally at addition
- track - set externally at addition
- duration
- muted
- releaseSelf

Similar to UChain.
Should expand on UChain by adding PolyTop Client integration (i.e. continuous playback polyphony...)
*/
var
<>topNodeMap = nil,
<muted = false;

*new {|node|
/* empty new
*/
^super.new.init(node);
}

init {|node|
/* empty init
*/
if (node.class != TopNodeMap, {
Error("UPolyState: node not of class TopNodeMap").throw;
});

this.topNodeMap = node;

// UEvent fields, TODO: for now all UPolyStates are self releasing, infinite and not muted. Update once continuous playback implemented.
duration = 2; // todo: why can't this be this.duration? I don't understand something about getters/setters and inheritence in SC...
("UPolyState: Initialization complete.").postln;
}

duration_ {|dur|
duration = dur;
}

isPausable_ {
Error.new("Not implemented!").throw;
}

waitTime {
Error.new("Not implemented!").throw;
}



start {
//play TopNode contents, called by UScore
this.topNodeMap
.getContents()
.playDiscrete();
}

release {
// TODO: once continuous is implemented, update this if needed.
}

getAllUChains {
("getAllUChains() of UPolyState called!").postln;
^this } // allows UScore to recursively get all UChains (in this case, UPolyStates)

muted_ {|muteState|
// TODO: implement with continuous playback
muted = muteState;
}

}
*/

/*
TopGenAlg {
// TODO: finish once concepts of walk%, walkAbs, walktrend and walklearn are complete
	var
	algName = "default";
	trans = nil, // transformation on a single number
	algParams = nil; // current state of trans params
	/* algParams:
	- stepSize - a single step size
	- stepCount - how many steps to take in single transformation
	- stepShape - if stepCount > 1, stepShape defines an envelope in range[0, stepSize] of given shape
	*/

	/* algorithm that takes in PolyContents and returns PolyContents. Used in generation phase of PolyTop
	@params:
	- Function trans(double value)
	- Dictionary algParams
	@return: PolyContents polyContents
	*/
	*new {|algName, trans, algParams|
		/* empty new
		*/
		^super.new.init(algName, trans, algParams);
	}

	*newDefault {|type|
		/* creates new default generative algorithm
		*/
		if (type.class != Symbol, { Error("TopGenAlg.newDefault: alg type not of type Symbol").throw; });
		switch (type,
			\walkPercent, {
				this.algParams = Dictionary.newFrom([\stepSize, 1, \nSteps, 1, \stepShape, nil]);
				this.algName = "walkPercent" ++ this.algParams.at(\stepSize);
				this.trans = foobar; // DEBUG temp
			},
			\walkMidi, { Error("TopGenAlg.newDefault: alg type /walkMidi is not implemented").throw; },
			\walkTrend, { Error("TopGenAlg.newDefault: alg type /walkTrend is not implemented").throw; },
			\walkLearn, { Error("TopGenAlg.newDefault: alg type /walkLearn is not implemented").throw; },
			{ Error("TopGenAlg.newDefault: " + type + " is not a default algorithm type").throw; }
		);

		^super.new.init(trans, algParams);
	}

	init {|trans, algParams|
		/* empty init
		*/
		if (trans.class != Function, {Error.new("TopGenAlg.init(): trans not of type Function")});
		if (algParams.class != Dictionary, {Error.new("TopGenAlg.init(): algorithm params not of type Dictionary")});

		this.trans = trans;
		this.algParams = algParams;
	}

	value {|polyContents|
		this.genAlg.value(polyContents);
	}

	_{|polyContents|
		this.value(polyContents);
	}
}*/