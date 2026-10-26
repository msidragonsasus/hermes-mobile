/** Deliberately small deterministic DOM test double, not a browser or Android renderer.
 * Covers this suite's controlled fixtures. Does not fetch URLs or execute page scripts.
 * Native CSS, layout, events, and Android integration still require real-browser tests.
 */
const vm = require('node:vm');
class Element {
  constructor(tag, attrs, doc) {this.tagName=tag.toUpperCase();this.attrs=attrs;this.doc=doc;this.children=[];this.parentElement=null;this.nodeType=1;this._value=attrs.value||'';this.style={};for(const piece of (attrs.style||'').split(';')){const [k,v]=piece.split(':');if(k&&v)this.style[k.trim().replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]=v.trim()}}
  getAttribute(n){return Object.hasOwn(this.attrs,n)?this.attrs[n]:null}
  hasAttribute(n){return Object.hasOwn(this.attrs,n)}
  setAttribute(n,v){this.attrs[n]=v}
  get previousElementSibling(){if(!this.parentElement)return null;const siblings=this.parentElement.children.filter(e=>typeof e!=='string');return siblings[siblings.indexOf(this)-1]||null}
  get id(){return this.attrs.id||''} get name(){return this.attrs.name||''}
  get type(){return (this.attrs.type||(this.tagName==='INPUT'?'text':this.tagName==='BUTTON'?'submit':'')).toLowerCase()}
  get autocomplete(){return this.attrs.autocomplete||''}
  get readOnly(){return this.hasAttribute('readonly')}
  get value(){return this._value} set value(v){this._value=v}
  get form(){if(this.hasAttribute('form'))return this.doc.getElementById(this.attrs.form);return this.parentElement?.closest('form')||null}
  get formAction(){return new URL(this.attrs.formaction||this.doc.baseURI,this.doc.baseURI).href}
  get formMethod(){return this.attrs.formmethod||'get'} get formTarget(){return this.attrs.formtarget||''}
  get labels(){return this.doc.querySelectorAll('label').filter(e=>e.attrs.for===this.id&&this.id||this.closest('label')===e)}
  get isConnected(){let e=this;while(e.parentElement)e=e.parentElement;return e===this.doc.root}
  get textContent(){return this.children.map(e=>typeof e==='string'?e:e.textContent).join('')}
  matches(selector){return selector.split(',').some(s=>{s=s.trim();if(s===':disabled')return this.hasAttribute('disabled')||!!this.closest('fieldset[disabled]');if(s.includes(':not(')){const [base,neg]=s.split(':not(');return this.matches(base)&&!this.matches(neg.slice(0,-1))}const tag=s.match(/^[a-z]+/i)?.[0];if(tag&&this.tagName!==tag.toUpperCase())return false;const klass=s.match(/\.([\w-]+)/)?.[1];if(klass&&!(this.attrs.class||'').split(/\s+/).includes(klass))return false;for(const a of s.matchAll(/\[([^\]=]+)(?:=["']?([^\]"']*)["']?)?\]/g)){if(!this.hasAttribute(a[1])||(a[2]!==undefined&&this.getAttribute(a[1])!==a[2]))return false}return !!(tag||klass||s.includes('['))})}
  closest(selector){for(let e=this;e;e=e.parentElement)if(e.matches(selector))return e;return null}
  querySelectorAll(selector){const out=[];for(const e of this.children)if(typeof e!=='string'){if(e.matches(selector))out.push(e);out.push(...e.querySelectorAll(selector))}return out}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null}
  getClientRects(){for(let e=this;e;e=e.parentElement)if(e.style.display==='none'||e.hasAttribute('hidden'))return [];return this.type==='hidden'?[]:[this.getBoundingClientRect()]}
  getBoundingClientRect(){const left=Number.parseInt(this.style.left||'0'),top=Number.parseInt(this.style.top||'0');const width=Number.parseInt(this.style.width||'200'),height=Number.parseInt(this.style.height||'30');return{width,height,left,top,right:left+width,bottom:top+height}}
}
class Input extends Element {}
Object.defineProperty(Input.prototype,'value',{get(){return this._value},set(v){this._value=v}});
class Form extends Element {}
for(const [name,get] of Object.entries({method(){return this.attrs.method||'get'},action(){return new URL(this.attrs.action||this.doc.baseURI,this.doc.baseURI).href},target(){return this.attrs.target||''},elements(){return this.doc.querySelectorAll('input,textarea,select,button').filter(e=>e.form===this)}}))Object.defineProperty(Form.prototype,name,{get,set(v){this.attrs[name]=v}});
function parse(markup,url){const doc={baseURI:url,visibilityState:'visible'};doc.root=new Element('body',{},doc);doc.querySelectorAll=s=>doc.root.querySelectorAll(s);doc.querySelector=s=>doc.querySelectorAll(s)[0]||null;doc.getElementById=id=>doc.querySelectorAll('[id]').find(e=>e.id===id)||null;let current=doc.root;const tokens=markup.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi,'').match(/<[^>]*>|[^<]+/g)||[];for(const token of tokens){if(token.startsWith('<!'))continue;if(token.startsWith('</')){current=current.parentElement||doc.root;continue}if(token.startsWith('<')){const name=token.match(/^<([\w-]+)/)?.[1];if(!name)continue;const attrs={};for(const a of token.slice(name.length+1,-1).matchAll(/([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g))attrs[a[1]]=a[2]??a[3]??a[4]??'';const Type=name==='input'?Input:name==='form'?Form:Element;const e=new Type(name,attrs,doc);e.parentElement=current;current.children.push(e);if(!['input','meta','br','img','link','hr'].includes(name))current=e}else current.children.push(token)}return doc}
let route;
const page={
  async goto(url){let markup='';await route({request:()=>({isNavigationRequest:()=>true,url:()=>url,method:()=> 'GET'}),fulfill:async o=>{markup=o.body},abort:async()=>{throw Error('Unexpected request')}});this.doc=parse(markup,url);this.location=new URL(url);this.context=vm.createContext({document:this.doc,location:this.location,URL,performance:{getEntriesByType:()=>[{serverTiming:[{name:'hermes',description:'synthetic-document-token'}]}]},HTMLInputElement:Input,HTMLFormElement:Form,getComputedStyle:e=>({display:e.style.display||'block',visibility:e.style.visibility||'visible',opacity:e.style.opacity||'1',overflowX:e.style.overflowX||e.style.overflow||'visible',overflowY:e.style.overflowY||e.style.overflow||'visible'}),history:{pushState:(a,b,url)=>{this.context.location=new URL(url,this.context.location.href)}}});this.context.window={innerWidth:1280,innerHeight:900};this.context.window.top=this.context.window},
  async evaluate(code){return vm.runInContext(typeof code==='function'?'('+code.toString()+')()':code,this.context,{timeout:1000})},
  locator(selector){return{evaluateAll:async f=>f(this.doc.querySelectorAll(selector))}}
};
module.exports={chromium:{launch:async()=>({newContext:async()=>({newPage:async()=>page,route:async(pattern,handler)=>{route=handler}}),close:async()=>{},version:async()=> 'Deterministic Node DOM test double (NOT Chromium)'})}};
