import React, { useState } from 'react';

// A poorly written React component
function Count() {
  const [count, setCount] = useState(0);
  
  // The following function has several issues
  function handleClick() {
    // Mutating state directly (critical issue)
    count = count + 1; // This is not how state should be updated in React!
  }

  // Side-effects inside render method (critical issue)
  if (count > 10) {
    alert("Count is greater than 10!"); // Side-effects like this should not be inside render method!
  }

  // Inline functions inside render (critical issue)
  return (
    <div>
      <button onClick={() => setCount(count + 1)}>Increment</button> {/* Inline function creation */}
      <p>{count}</p>
    </div>
  );
}

export default Count;
