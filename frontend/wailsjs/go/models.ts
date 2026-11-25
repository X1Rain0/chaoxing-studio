export namespace chaoxing {
	
	export class Course {
	    courseId: string;
	    classId: string;
	    chatId: string;
	    courseName: string;
	    className: string;
	
	    static createFrom(source: any = {}) {
	        return new Course(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.courseId = source["courseId"];
	        this.classId = source["classId"];
	        this.chatId = source["chatId"];
	        this.courseName = source["courseName"];
	        this.className = source["className"];
	    }
	}
	export class Activity {
	    activeId: string;
	    name: string;
	    otherId: number;
	    kind: number;
	    kindName: string;
	    course: Course;
	    // Go type: time
	    startAt: any;
	    isPhoto: boolean;
	
	    static createFrom(source: any = {}) {
	        return new Activity(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.activeId = source["activeId"];
	        this.name = source["name"];
	        this.otherId = source["otherId"];
	        this.kind = source["kind"];
	        this.kindName = source["kindName"];
	        this.course = this.convertValues(source["course"], Course);
	        this.startAt = this.convertValues(source["startAt"], null);
	        this.isPhoto = source["isPhoto"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	
	export class SignResult {
	    ok: boolean;
	    message: string;
	    raw: string;
	    kind: number;
	    kindName: string;
	    activeId: string;
	    // Go type: time
	    at: any;
	    durationMs: number;
	
	    static createFrom(source: any = {}) {
	        return new SignResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.message = source["message"];
	        this.raw = source["raw"];
	        this.kind = source["kind"];
	        this.kindName = source["kindName"];
	        this.activeId = source["activeId"];
	        this.at = this.convertValues(source["at"], null);
	        this.durationMs = source["durationMs"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace engine {
	
	export class Options {
	    interval: number;
	    auto: boolean;
	    signCode: string;
	    latitude: string;
	    longitude: string;
	    address: string;
	    enc: string;
	    courseIds: string[];
	
	    static createFrom(source: any = {}) {
	        return new Options(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.interval = source["interval"];
	        this.auto = source["auto"];
	        this.signCode = source["signCode"];
	        this.latitude = source["latitude"];
	        this.longitude = source["longitude"];
	        this.address = source["address"];
	        this.enc = source["enc"];
	        this.courseIds = source["courseIds"];
	    }
	}
	export class Status {
	    accountId: string;
	    running: boolean;
	    interval: number;
	    // Go type: time
	    lastTick: any;
	    lastMessage: string;
	    signs: number;
	    checks: number;
	    failures: number;
	    // Go type: time
	    startedAt: any;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.accountId = source["accountId"];
	        this.running = source["running"];
	        this.interval = source["interval"];
	        this.lastTick = this.convertValues(source["lastTick"], null);
	        this.lastMessage = source["lastMessage"];
	        this.signs = source["signs"];
	        this.checks = source["checks"];
	        this.failures = source["failures"];
	        this.startedAt = this.convertValues(source["startedAt"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace main {
	
	export class BootstrapData {
	    version: string;
	    dataPath: string;
	    accounts: store.Account[];
	    records: store.Record[];
	    options: store.Options;
	    statuses: Record<string, engine.Status>;
	    // Go type: time
	    now: any;
	
	    static createFrom(source: any = {}) {
	        return new BootstrapData(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.dataPath = source["dataPath"];
	        this.accounts = this.convertValues(source["accounts"], store.Account);
	        this.records = this.convertValues(source["records"], store.Record);
	        this.options = this.convertValues(source["options"], store.Options);
	        this.statuses = this.convertValues(source["statuses"], engine.Status, true);
	        this.now = this.convertValues(source["now"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class LoginResult {
	    account: store.Account;
	    courses: chaoxing.Course[];
	
	    static createFrom(source: any = {}) {
	        return new LoginResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.account = this.convertValues(source["account"], store.Account);
	        this.courses = this.convertValues(source["courses"], chaoxing.Course);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace store {
	
	export class Account {
	    id: string;
	    phone: string;
	    name: string;
	    fid: string;
	    uid: string;
	    enabled: boolean;
	    // Go type: time
	    createdAt: any;
	    // Go type: time
	    lastLogin: any;
	    secret: string;
	
	    static createFrom(source: any = {}) {
	        return new Account(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.phone = source["phone"];
	        this.name = source["name"];
	        this.fid = source["fid"];
	        this.uid = source["uid"];
	        this.enabled = source["enabled"];
	        this.createdAt = this.convertValues(source["createdAt"], null);
	        this.lastLogin = this.convertValues(source["lastLogin"], null);
	        this.secret = source["secret"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}
	export class Options {
	    interval: number;
	    auto: boolean;
	    signCode: string;
	    latitude: string;
	    longitude: string;
	    address: string;
	
	    static createFrom(source: any = {}) {
	        return new Options(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.interval = source["interval"];
	        this.auto = source["auto"];
	        this.signCode = source["signCode"];
	        this.latitude = source["latitude"];
	        this.longitude = source["longitude"];
	        this.address = source["address"];
	    }
	}
	export class Record {
	    id: string;
	    accountId: string;
	    course: string;
	    className: string;
	    kind: string;
	    ok: boolean;
	    message: string;
	    // Go type: time
	    at: any;
	
	    static createFrom(source: any = {}) {
	        return new Record(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.accountId = source["accountId"];
	        this.course = source["course"];
	        this.className = source["className"];
	        this.kind = source["kind"];
	        this.ok = source["ok"];
	        this.message = source["message"];
	        this.at = this.convertValues(source["at"], null);
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

