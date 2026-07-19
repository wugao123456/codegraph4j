const KIND_COLORS = {
  file: '#6b7280', module: '#6b7280',
  class: '#f59e0b', struct: '#f59e0b',
  interface: '#eab308', trait: '#eab308', protocol: '#eab308',
  function: '#3b82f6', method: '#2563eb',
  property: '#06b6d4', field: '#06b6d4',
  variable: '#10b981', constant: '#10b981',
  enum: '#a855f7', enum_member: '#a855f7',
  type_alias: '#8b5cf6', namespace: '#94a3b8',
  parameter: '#94a3b8', import: '#cbd5e1', export: '#cbd5e1',
  route: '#ef4444', component: '#ec4899'
};
const DEFAULT_COLOR = '#94a3b8';

let network = null;
let nodesData = null;
let edgesData = null;
let requestId = 0;
let nodeInfoMap = {};

function initNetwork() {
  document.getElementById('loading').style.display = 'none';

  nodesData = new vis.DataSet([]);
  edgesData = new vis.DataSet([]);
  const container = document.getElementById('network');
  const data = { nodes: nodesData, edges: edgesData };
  const options = {
    nodes: {
      shape: 'dot',
      font: { color: '#e5e7eb', size: 12 },
      borderWidth: 1,
      scaling: { min: 6, max: 42 }
    },
    edges: {
      color: { color: '#374151', highlight: '#60a5fa' },
      font: { color: '#6b7280', size: 9, strokeWidth: 0 },
      smooth: { type: 'continuous' }
    },
    physics: {
      enabled: true,
      solver: 'forceAtlas2Based',
      forceAtlas2Based: {
        gravitationalConstant: -60, centralGravity: 0.008,
        springLength: 110, springConstant: 0.06,
        damping: 0.5, avoidOverlap: 1
      },
      stabilization: { iterations: 250, fit: true },
      minVelocity: 0.6, timestep: 0.4
    },
    interaction: {
      hover: true, tooltipDelay: 100,
      dragNodes: true, dragView: true,
      zoomView: true, zoomSpeed: 0.6,
      multiselect: true, navigationButtons: false, keyboard: false
    }
  };
  network = new vis.Network(container, data, options);

  network.once('stabilizationIterationsDone', () => {
    network.fit();
    network.setOptions({ physics: { enabled: false } });
  });

  const MIN_ZOOM = 0.15, MAX_ZOOM = 4.0;
  network.on('zoom', () => {
    const s = network.getScale();
    if (s < MIN_ZOOM) network.moveTo({ scale: MIN_ZOOM });
    else if (s > MAX_ZOOM) network.moveTo({ scale: MAX_ZOOM });
  });

  network.on('click', (params) => {
    if (params.nodes.length > 0) {
      const nodeId = params.nodes[0];
      showNodeInfo(nodeId);
    } else {
      closeNodeInfo();
    }
  });

  network.on('oncontext', (params) => {
    params.event.preventDefault();
    if (params.nodes.length > 0) {
      const nodeId = params.nodes[0];
      expandNode(nodeId);
    }
  });

  document.getElementById('query-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doQuery();
  });
}

async function apiCall(method, params) {
  const id = 'web-' + (++requestId);
  const response = await fetch('/api/mcp', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params })
  });
  return response.json();
}

async function doQuery() {
  const mode = document.getElementById('mode-select').value;
  const query = document.getElementById('query-input').value.trim();
  const degree = parseInt(document.getElementById('degree-select').value) || 1;
  const btn = document.getElementById('query-btn');
  const status = document.getElementById('status');

  btn.disabled = true;
  status.textContent = 'Querying...';
  document.getElementById('loading').style.display = 'block';
  closeNodeInfo();

  try {
    const params = { maxNodes: 250, degree };

    if (mode === 'symbol' && query) {
      params.symbol = query;
    } else if (mode === 'file' && query) {
      params.file = query;
    }

    const result = await apiCall('graph/view', params);

    if (result.error) {
      status.textContent = 'Error: ' + result.error.message;
      document.getElementById('loading').style.display = 'none';
      btn.disabled = false;
      return;
    }

    const data = result.result;
    if (!data || !data.nodes) {
      status.textContent = 'No data returned';
      document.getElementById('loading').style.display = 'none';
      btn.disabled = false;
      return;
    }

    renderGraph(data);
    status.textContent = 'OK — ' + data.stats.totalNodes + ' nodes, ' + data.stats.totalEdges + ' edges';
  } catch (err) {
    status.textContent = 'Error: ' + err.message;
  }

  document.getElementById('loading').style.display = 'none';
  btn.disabled = false;
}

function renderGraph(data) {
  nodesData.clear();
  edgesData.clear();
  nodeInfoMap = {};

  const nodes = (data.nodes || []).map(n => {
    const node = {
      id: n.id,
      label: n.label,
      title: n.title,
      group: n.group,
      color: n.color || KIND_COLORS[n.group] || DEFAULT_COLOR,
      value: n.value || 10
    };
    nodeInfoMap[n.id] = n;
    return node;
  });
  nodesData.add(nodes);

  const edges = (data.edges || []).map(e => ({
    from: e.from,
    to: e.to,
    label: e.label,
    arrows: e.arrows || 'to',
    title: e.title || e.label
  }));
  edgesData.add(edges);

  const kinds = data.stats && data.stats.kinds ? data.stats.kinds : [];
  const legendHtml = kinds.map(kind => {
    const color = KIND_COLORS[kind] || DEFAULT_COLOR;
    return '<span class="legend-item"><span class="swatch" style="background:' + color + '"></span>' + kind + '</span>';
  }).join('');
  document.getElementById('legend').innerHTML = legendHtml || '<span style="color:#6b7280">No data</span>';

  document.getElementById('stats-display').textContent =
    (data.stats ? data.stats.totalNodes : 0) + ' symbols · ' +
    (data.stats ? data.stats.totalEdges : 0) + ' relationships';

  network.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
}

function showNodeInfo(nodeId) {
  const node = nodeInfoMap[nodeId];
  if (!node) return;

  const panel = document.getElementById('node-info-panel');
  const title = document.getElementById('node-info-title');
  const content = document.getElementById('node-info-content');

  title.textContent = node.label;
  content.innerHTML = `
    <div class="info-row"><span class="info-label">ID:</span><span class="info-value">${node.id}</span></div>
    <div class="info-row"><span class="info-label">Type:</span><span class="info-value" style="color:${node.color}">${node.group}</span></div>
    <div class="info-row"><span class="info-label">Value:</span><span class="info-value">${node.value}</span></div>
    <div class="info-row"><span class="info-label">File:</span><span class="info-value">${node.file || 'N/A'}</span></div>
    <div class="info-row"><span class="info-label">Details:</span><span class="info-value">${node.title ? node.title.replace(/\n/g, '<br>') : 'N/A'}</span></div>
  `;

  panel.classList.add('show');
}

function closeNodeInfo() {
  document.getElementById('node-info-panel').classList.remove('show');
}

async function expandNode(nodeId) {
  const node = nodeInfoMap[nodeId];
  if (!node) return;

  const degree = parseInt(document.getElementById('degree-select').value) || 1;
  const status = document.getElementById('status');

  status.textContent = 'Expanding ' + node.label + '...';

  try {
    const result = await apiCall('graph/view', {
      symbol: node.label,
      maxNodes: 500,
      degree
    });

    if (result.error) {
      status.textContent = 'Expand error: ' + result.error.message;
      return;
    }

    const data = result.result;
    if (!data || !data.nodes) {
      status.textContent = 'No data returned';
      return;
    }

    const existingIds = new Set(nodesData.getIds());
    const newNodes = [];
    const newEdges = [];

    for (const n of data.nodes) {
      if (!existingIds.has(n.id)) {
        newNodes.push({
          id: n.id,
          label: n.label,
          title: n.title,
          group: n.group,
          color: n.color || KIND_COLORS[n.group] || DEFAULT_COLOR,
          value: n.value || 10
        });
        nodeInfoMap[n.id] = n;
      }
    }

    const existingEdgeKeys = new Set();
    edgesData.forEach(e => existingEdgeKeys.add(e.from + '|' + e.to));
    for (const e of data.edges) {
      const key = e.from + '|' + e.to;
      if (!existingEdgeKeys.has(key)) {
        newEdges.push({
          from: e.from,
          to: e.to,
          label: e.label,
          arrows: e.arrows || 'to',
          title: e.title || e.label
        });
        existingEdgeKeys.add(key);
      }
    }

    if (newNodes.length > 0) nodesData.add(newNodes);
    if (newEdges.length > 0) edgesData.add(newEdges);

    status.textContent = 'Expanded — +' + newNodes.length + ' nodes, +' + newEdges.length + ' edges';
    network.fit({ animation: { duration: 500 } });
  } catch (err) {
    status.textContent = 'Expand error: ' + err.message;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  initNetwork();
});
