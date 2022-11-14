const express = require("express");
const fs = require("fs");
const mongoose = require("mongoose");
const router = express.Router();

require("../models/ProjectSummary");
require("../models/SessionSummary");
require("../models/SessionProfile");

const ProjectSummary = mongoose.model("ProjectSummary");
const SessionSummary = mongoose.model("SessionSummary");
const SessionProfile = mongoose.model("SessionProfile");

const app = express();
const http = require('http');
var path = require('path');


const MONGO_DB_HOST = process.env.MONGO_DB_HOST || "localhost"
const MONGO_DB_PORT = process.env.MONGO_DB_PORT || "27017"
const MongoServerUrl = MONGO_DB_HOST + ":" + MONGO_DB_PORT;

mongoose.connect("mongodb://"+MongoServerUrl+"/build_scans", { useNewUrlParser: true });
mongoose.Promise = global.Promise;

router.get("/", (req, res) => {
	fs.readFile("index.html", (error, content) => res.write(content));

});

router.get("/static/feather-icons/:fileName", function(req, res) {
	const fileName = req.params.fileName;
	const localPath = path.resolve(__dirname + "/../node_modules/feather-icons/dist/" + fileName);
	console.log(localPath);
    res.sendFile(localPath);
});

router.get("/static/vis-timeline/:fileName", function(req, res) {
	const fileName = req.params.fileName;
	const localPath = path.resolve(__dirname + "/../node_modules/vis-timeline/dist/" + fileName);
	console.log(localPath);
    res.sendFile(localPath);
});

router.get("/static/vis-network/:fileName", function(req, res) {
	const fileName = req.params.fileName;
	const localPath = path.resolve(__dirname + "/../node_modules/vis-network/dist/" + fileName);
	console.log(localPath);
    res.sendFile(localPath);
});

// ForbiddenError: Forbidden
//     at createHttpError (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/send/index.js:979:12)
//     at SendStream.error (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/send/index.js:270:31)
//     at SendStream.pipe (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/send/index.js:549:12)
//     at sendfile (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/response.js:1130:8)
//     at ServerResponse.sendFile (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/response.js:449:3)
//     at /Users/gorohovart/dev/projects/maven-build-scanner/server/routes/index.js:35:9
//     at Layer.handle [as handle_request] (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/router/layer.js:95:5)
//     at next (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/router/route.js:144:13)
//     at Route.dispatch (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/router/route.js:114:3)
//     at Layer.handle [as handle_request] (/Users/gorohovart/dev/projects/maven-build-scanner/server/node_modules/express/lib/router/layer.js:95:5)

router.get("/api/v1/project-summaries", (req, res) => {
	ProjectSummary.find()
		.then((projectSummaries) => {
			const out = {};
			projectSummaries.forEach(projectSummary => {
				out[projectSummary.id] = projectSummary;
			});
			res.json(out);
		})
		.catch(() => {
			res.status(500);
			res.send();
		});

});
router.get("/api/v1/session-summaries/:projectId", (req, res) => {
	const projectId = req.params.projectId;
	const groupId = projectId.replace(/:.*/, "");
	const artifactId = projectId.replace(/.*:/, "");
	SessionSummary.find({"project.groupId": groupId, "project.artifactId": artifactId})
		.then((sessionSummaries) => {
			const out = {};
			sessionSummaries.forEach(sessionSummary => {
				out[sessionSummary.id] = sessionSummary;
			});
			res.json(out);
		})
		.catch(() => {
			res.status(500);
			res.send();
		});

});
router.get("/api/v1/session-profiles/:projectId/:sessionId", (req, res) => {
	const projectId = req.params.projectId;
	const sessionId = req.params.sessionId;
	const groupId = projectId.replace(/:.*/, "");
	const artifactId = projectId.replace(/.*:/, "");
	SessionProfile.findOne({"project.groupId": groupId, "project.artifactId": artifactId, "id": sessionId})
		.then((sessionProfile) => {
			res.json(sessionProfile);
		})
		.catch(() => {
			res.status(500);
			res.send();
		});

});

router.get("/deps/:source/:target", (req, res) => {
	const source = req.params.source;
	const target = req.params.target;
	console.log("source: " + source + ", target: " + target);
	// const groupId = projectId.replace(/:.*/, "");
	// const artifactId = projectId.replace(/.*:/, "");
	http.get('http://localhost:3005/deps/' + source + '/' + target, (resp) => {
		let data = '';
	  
		// A chunk of data has been received.
		resp.on('data', (chunk) => {
		  data += chunk;
		});
	  
		// The whole response has been received. Print out the result.
		resp.on('end', () => {
		  console.log(JSON.parse(data));
		  res.json(data);
		});
	  
	  }).on("error", (err) => {
		console.log("Error: " + err.message);
	  });


	// SessionProfile.findOne({"project.groupId": groupId, "project.artifactId": artifactId, "id": sessionId})
	// 	.then((sessionProfile) => {
	// 		res.json(sessionProfile);
	// 	})
	// 	.catch(() => {
	// 		res.status(500);
	// 		res.send();
	// 	});

});


router.get("/graph/:project", (req, res) => {
	const project = req.params.project;
	console.log("graph for: " + project);
	http.get('http://localhost:3005/graph/' + project, (resp) => {
		let data = '';
	  
		// A chunk of data has been received.
		resp.on('data', (chunk) => {
		  data += chunk;
		});
	  
		// The whole response has been received. Print out the result.
		resp.on('end', () => {
		  console.log(JSON.parse(data));
		  res.json(data);
		});
	  
	  }).on("error", (err) => {
		console.log("Error: " + err.message);
	  });
});

module.exports = router;
